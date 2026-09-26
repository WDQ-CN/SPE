package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 失败路径审计的**提交**语义（回归用例，2026-09-26 由 e2e 实跑发现）。
 *
 * <p>本类刻意不标 `@Transactional`：只有没有外层事务时，`JdbcTemplate` 的读才走自动提交，
 * 从而**只看到已提交的数据**。
 *
 * <p>背景：`AuthService.login` 曾标 `@Transactional`，失败时「写审计 → 抛 1002」在同一个事务里，
 * 抛异常把刚写的 `AUTH_LOGIN_FAILED` 一起回滚——`audit_log` 里只有成功记录，`auto_increment`
 * 却比行数多（回滚留下的空位）。当时 `SystemApiIntegrationTest` 是绿的，因为它的 `@Transactional`
 * 让回滚后的行在事务内依旧可见。这条用例把该缺陷钉死：审计写入必须能独立于业务失败存活。
 */
class LoginAuditPersistenceIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("登录失败：1002 之外，AUTH_LOGIN_FAILED 审计真的落库（跨事务可见，即已提交）")
  void loginFailureAuditIsCommittedDespiteRollback() throws Exception {
    mockMvc
        .perform(
            json(post("/api/auth/login"), Map.of("username", ADMIN_USERNAME, "password", "definitely-wrong")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1002));

    Long committed =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'AUTH_LOGIN_FAILED' AND operator_name = ?",
            Long.class,
            ADMIN_USERNAME);
    assertThat(committed).as("失败审计必须提交，否则回滚会把它一起抹掉").isNotNull().isPositive();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT operator_id FROM audit_log WHERE action = 'AUTH_LOGIN_FAILED' AND operator_name = ?"
                    + " ORDER BY id DESC LIMIT 1",
                Long.class,
                ADMIN_USERNAME))
        .as("登录失败时账号可能根本不存在，操作人 id 应为空")
        .isNull();
  }

  @Test
  @DisplayName("未知账号同样留痕：审计的账号名取自输入")
  void unknownAccountAttemptIsAudited() throws Exception {
    String attempted = "no-such-account-e2e";

    mockMvc
        .perform(json(post("/api/auth/login"), Map.of("username", attempted, "password", "whatever")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1002));

    Long committed =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'AUTH_LOGIN_FAILED' AND operator_name = ?",
            Long.class,
            attempted);
    assertThat(committed).isNotNull().isPositive();
  }

  @Test
  @DisplayName("登录成功：AUTH_LOGIN_SUCCESS 提交，operator_id 为真实 uid")
  void loginSuccessAuditIsCommitted() throws Exception {
    login(ADMIN_USERNAME, ADMIN_PASSWORD);

    Long operatorId =
        jdbcTemplate.queryForObject(
            "SELECT operator_id FROM audit_log WHERE action = 'AUTH_LOGIN_SUCCESS' AND operator_name = ?"
                + " ORDER BY id DESC LIMIT 1",
            Long.class,
            ADMIN_USERNAME);
    assertThat(operatorId).isEqualTo(userIdOf(ADMIN_USERNAME));
  }
}
