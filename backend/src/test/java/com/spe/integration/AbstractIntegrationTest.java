package com.spe.integration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * 集成测试基类（09 D7 / 10 §E6）：真起 MySQL 8.4 容器 + 走完整安全链（`MockMvc` 带 servlet 过滤器）。
 *
 * <p>三条约定：
 * <ol>
 *   <li>JWT 密钥由本类注入（生产由环境变量给，缺失时 `JwtService` 直接启动失败）；
 *   <li>认证走**真实登录接口**拿令牌，不手搓 JWT——顺带把登录链路也纳入每个集成用例的前置；
 *   <li>本类**不加** `@Transactional`：需要用例间隔离的测试类自己标注。基类标了它会让「回滚语义」
 *       失去覆盖——失败路径写下的审计行在同一事务里仍然可见，于是「登录失败审计被回滚」这种缺陷
 *       会被判成绿（实测踩过，见 `LoginAuditPersistenceIntegrationTest`）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
    properties = {
      "app.jwt.secret=spe-integration-test-secret-0123456789abcdef",
      "app.integration.webhook.allow-private-targets=true"
    })
public abstract class AbstractIntegrationTest {

  /** 种子账号（`V5__seed_admin.sql`，仅演示环境）。 */
  protected static final String ADMIN_USERNAME = "admin";

  protected static final String ADMIN_PASSWORD = "Admin@12345";

  protected static final String DEFAULT_PASSWORD = "Passw0rd!";

  /**
   * 「故意失败登录」专用的来源 IP。登录限流器（03 §16 的 SEC-5）按**账号**与**来源 IP**两个维度
   * 独立计数，且它是进程内单例、不随测试事务回滚——多个用例在同一个 Spring 上下文里各自制造失败登录时，
   * 计数会互相叠加：`admin` 被三处共用（账号维度阈值 5），再多一条就会把它锁死，使后续所有用例的前置
   * 登录抛异常，表现为「改一个不相干的用例、另一个用例莫名变红」。统一用这个独立 IP，并要求调用方
   * 使用**专用探针账号**（见 {@link #failedLogin}）。
   */
  protected static final String FAILURE_PROBE_IP = "198.18.0.99";

  /** 失败登录专用账号名：库里不存在，因此审计的 operator_name 就是输入值、operator_id 为空。 */
  protected static final String FAILURE_PROBE_ACCOUNT = "w4-probe-unknown-account";

  @Autowired protected MockMvc mockMvc;

  @Autowired protected ObjectMapper objectMapper;

  @Autowired protected JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body)
      throws Exception {
    return builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
  }

  protected AbstractMockHttpServletRequestBuilder<?> authed(AbstractMockHttpServletRequestBuilder<?> builder, String token) {
    return builder.header("Authorization", "Bearer " + token);
  }

  /** 走真实登录接口取令牌；失败即抛，避免后续断言在「其实是没登录」的前提下变绿。 */
  protected String login(String username, String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                json(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login"),
                    Map.of("username", username, "password", password)))
            .andReturn();
    String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    String token = objectMapper.readTree(body).path("data").path("token").asText();
    if (token.isEmpty()) {
      throw new IllegalStateException("登录失败，无法继续：status=" + result.getResponse().getStatus() + " body=" + body);
    }
    return token;
  }

  /**
   * 制造一次**失败**的登录，返回 ResultActions 供调用方继续 andExpect（不在这里断言状态码，
   * 因为「用哪个码失败」本身可能是被测行为）。
   *
   * <p>统一走这里而不是各自 {@code mockMvc.perform}，是为了让所有失败登录都带上独立来源 IP
   * （{@link #FAILURE_PROBE_IP}）——否则它们会往限流器的 IP 维度计数里叠加，制造出跨用例的隐性顺序依赖。
   * 账号请用 {@link #FAILURE_PROBE_ACCOUNT} 或本用例专属的名字，**不要用 admin**。
   */
  protected ResultActions failedLogin(String username, String password) throws Exception {
    return mockMvc.perform(
        json(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login"),
                Map.of("username", username, "password", password))
            .with(
                request -> {
                  request.setRemoteAddr(FAILURE_PROBE_IP);
                  return request;
                }));
  }

  protected long userIdOf(String username) {
    Long id =
        jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    if (id == null) {
      throw new IllegalStateException("用户不存在：" + username);
    }
    return id;
  }

  protected List<Map<String, Object>> auditRows(String action) {
    return jdbcTemplate.queryForList(
        "SELECT operator_id, operator_name, action, target_type, target_id, detail, ip"
            + " FROM audit_log WHERE action = ? ORDER BY id DESC",
        action);
  }
}
