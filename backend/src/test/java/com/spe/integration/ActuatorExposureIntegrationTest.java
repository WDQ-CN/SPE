package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.context.ApplicationContext;

/**
 * actuator 暴露面守卫（03 §19）。**这是本类存在的唯一理由**：
 *
 * <p>一旦有人把 `management.endpoints.web.exposure.include` 改成 `"*"`，`/actuator/env` 与
 * `/actuator/configprops` 会把配置值原样打印出来——其中就有 `JWT_SECRET`。那种改动**不会让任何功能报错**，
 * 只会安静地把令牌签名密钥挂到网上，所以必须有一条会变红的测试盯着它（而不是靠人记得）。
 *
 * <p>同时锁定三个端点各自的访问门槛：health 免认证（容器 healthcheck 要用）、metrics/prometheus 需
 * ADMIN（指标含 JVM/HTTP/DB 信息，普通登录用户没必要看）——两层一起才是纵深防御。
 */
class ActuatorExposureIntegrationTest extends AbstractIntegrationTest {

  private static final Set<String> ALLOWED_ENDPOINTS = Set.of("health", "metrics", "prometheus");

  @Autowired private WebEndpointProperties webEndpointProperties;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private ApplicationContext applicationContext;

  @Test
  @DisplayName("MeterRegistry 实现是 Prometheus，且抓取端点已注册（否则 /actuator/prometheus 会 404）")
  void prometheusRegistryAndScrapeEndpointAreRegistered() {
    // 用类名字符串比较，避免把「micrometer 的包名在版本间改过」这件事写死进编译期依赖
    assertThat(meterRegistry.getClass().getSimpleName())
        .as("MeterRegistry 的实际实现（期望 PrometheusMeterRegistry）")
        .isEqualTo("PrometheusMeterRegistry");
    assertThat(applicationContext.containsBean("prometheusEndpoint"))
        .as("抓取端点 bean（prometheusEndpoint）是否注册——没注册则 /actuator/prometheus 会 404")
        .isTrue();
  }

  @Test
  @DisplayName("暴露的端点恰好是 health/metrics/prometheus，绝不包含 env 或 configprops")
  void onlyThreeEndpointsAreExposed() {
    Set<String> exposed = webEndpointProperties.getExposure().getInclude();

    assertThat(exposed)
        .as("白名单只允许这三个；写成 \"*\" 会让 /actuator/env 公开打印 JWT_SECRET")
        .isEqualTo(ALLOWED_ENDPOINTS);
    assertThat(exposed)
        .doesNotContain("*", "env", "configprops", "heapdump", "threaddump", "beans", "mappings");
    assertThat(webEndpointProperties.getExposure().getExclude()).isEmpty();
  }

  @Test
  @DisplayName("health 免认证；metrics/prometheus 匿名访问被拒（401，不是 200）")
  void healthIsPublicButMetricsRequireAuthentication() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("管理员能抓到 Prometheus 文本，且其中含自定义业务指标、不含口令")
  void adminCanScrapeCustomMetrics() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    // 先触发一次阈值读取与一次失败登录，确保这几个 Counter 有值（Prometheus 文本只列已注册的指标，
    // 但零值 counter 是否出现取决于实现细节，先产生真实流量再断言更稳）
    mockMvc.perform(authed(get("/api/settings/grade-thresholds"), adminToken)).andExpect(status().isOk());
    failedLogin(FAILURE_PROBE_ACCOUNT, "definitely-wrong").andExpect(status().isUnauthorized());

    String body =
        mockMvc
            .perform(authed(get("/actuator/prometheus"), adminToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .as("自定义业务指标必须出现在抓取结果里（否则等于没接上）")
        .contains("spe_login_limiter_failures")
        .contains("spe_login_limiter_tracked_keys")
        .contains("spe_settings_thresholds_loads");
    assertThat(body).as("抓取输出里不得出现口令").doesNotContain(ADMIN_PASSWORD);
    assertThat(body).as("抓取输出里不得出现任何账号名标签").doesNotContain(FAILURE_PROBE_ACCOUNT);
  }

  @Test
  @DisplayName("非 ADMIN 角色读指标被拒（403）——即使将来多暴露了端点，这一层仍挡得住")
  void nonAdminCannotReadMetrics() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String probeUsername = "metrics-probe-evaluator";
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", probeUsername,
                        "password", DEFAULT_PASSWORD,
                        "name", "指标探针",
                        "role", "EVALUATOR",
                        "department", "QUALITY")),
                adminToken))
        .andExpect(status().isOk());

    String evaluatorToken = login(probeUsername, DEFAULT_PASSWORD);
    mockMvc
        .perform(authed(get("/actuator/metrics"), evaluatorToken))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(authed(get("/actuator/prometheus"), evaluatorToken))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("未暴露的端点即使以管理员访问也是 404（不是「仅需认证」）")
  void unexposedEndpointsAreNotFound() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockMvc.perform(authed(get("/actuator/env"), adminToken)).andExpect(status().isNotFound());
    mockMvc
        .perform(authed(get("/actuator/configprops"), adminToken))
        .andExpect(status().isNotFound());
  }
}
