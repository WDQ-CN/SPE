package com.spe.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
@TestPropertySource(properties = "app.jwt.secret=spe-integration-test-secret-0123456789abcdef")
public abstract class AbstractIntegrationTest {

  /** 种子账号（`V5__seed_admin.sql`，仅演示环境）。 */
  protected static final String ADMIN_USERNAME = "admin";

  protected static final String ADMIN_PASSWORD = "Admin@12345";

  protected static final String DEFAULT_PASSWORD = "Passw0rd!";

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

  protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
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
