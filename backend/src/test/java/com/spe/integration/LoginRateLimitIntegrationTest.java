package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录失败限流的端到端行为（03 §16 的 SEC-5）：连续失败达到阈值后，登录返回 **429 + 1003**，
 * 而不是继续无限次比对口令；同时只为「进入锁定」那一刻留一条 `AUTH_LOGIN_BLOCKED` 审计。
 *
 * <p>本用例刻意用**独立来源 IP**（`withRemoteAddr`）：限流器是进程内单例，IP 维度按来源累计，
 * 若与其它用例共用 MockMvc 的默认地址，次数会互相累加、让测试变得依赖执行顺序。
 */
@Transactional
class LoginRateLimitIntegrationTest extends AbstractIntegrationTest {

  private static final String PROBE_ACCOUNT = "rate_limit_probe";
  private static final String PROBE_IP = "198.18.0.77";

  private MockHttpServletRequestBuilder loginAttempt() throws Exception {
    return json(
            post("/api/auth/login"),
            Map.of("username", PROBE_ACCOUNT, "password", "definitely-wrong"))
        .with(
            request -> {
              request.setRemoteAddr(PROBE_IP);
              return request;
            });
  }

  @Test
  @DisplayName("连续失败到阈值 → 429 + 1003，并落一条 AUTH_LOGIN_BLOCKED")
  void repeatedFailuresGetRateLimited() throws Exception {
    for (int i = 0; i < 5; i++) {
      mockMvc
          .perform(loginAttempt())
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value(1002));
    }

    mockMvc
        .perform(loginAttempt())
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(1003));

    // 断言到具体那一行：只判非空的话，这条审计记到别的账号头上也一样通过。
    // 按 operator_name 过滤、而不是对全表 hasSize(1)：audit_log 是**整个测试 JVM 共享**的，
    // 别的用例（如留痕验收）也会制造自己的锁定记录（04-041）；而且登录审计是独立提交的，
    // 即使本类带 @Transactional 也回滚不掉它。
    List<Map<String, Object>> blocked =
        jdbcTemplate.queryForList(
            "SELECT operator_id, operator_name, detail FROM audit_log"
                + " WHERE action = 'AUTH_LOGIN_BLOCKED' AND operator_name = ? ORDER BY id DESC",
            PROBE_ACCOUNT);
    assertThat(blocked).as("只为「进入锁定」那一刻留一条，锁定期内的后续拒绝不再写库").hasSize(1);
    assertThat(blocked.get(0).get("operator_id")).as("锁定发生在认证之前，没有可信的 uid").isNull();
  }
}
