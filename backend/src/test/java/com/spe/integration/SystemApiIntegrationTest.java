package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserQueryFacade;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * system 上下文端到端集成用例（10 §3 W2 / §1 出口判据 2、4、5）。
 *
 * <p>覆盖的是单测（mock 掉 Mapper）验证不到的部分：真实 SQL（守卫 UPDATE、`LIMIT/OFFSET`、
 * `<foreach>` 批量、JSON 列 upsert）、真实 BCrypt 与 JWT、完整安全链（含方法级鉴权与越权留痕）。
 *
 * <p>本类标 `@Transactional` 换取用例间隔离（容器是共享的）；代价是**看不到回滚语义**——
 * 「失败路径写下的行是否真的提交」这类断言必须放在不加 `@Transactional` 的用例里，
 * 见 {@link LoginAuditPersistenceIntegrationTest}。
 */
@Transactional
class SystemApiIntegrationTest extends AbstractIntegrationTest {

  @Autowired private UserQueryFacade userQueryFacade;

  /** 建一个可用账号（走真实 POST /api/users），返回其 id。 */
  private long createUser(String token, String username, Role role) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", username,
                        "password", DEFAULT_PASSWORD,
                        "name", "测试用户",
                        "role", role.name(),
                        "department", "QUALITY")),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    return userIdOf(username);
  }

  @Test
  @DisplayName("登录成功：返回 token/name/roles/department 并留 AUTH_LOGIN_SUCCESS（operator_id 为真实 uid）")
  void loginSucceedsAndIsAudited() throws Exception {
    mockMvc
        .perform(json(post("/api/auth/login"), Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.token").isNotEmpty())
        .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"))
        .andExpect(jsonPath("$.data.department").value("OTHER"));

    List<Map<String, Object>> rows = auditRows("AUTH_LOGIN_SUCCESS");
    assertThat(rows).isNotEmpty();
    assertThat(rows.get(0).get("operator_id")).isEqualTo(userIdOf(ADMIN_USERNAME));
    assertThat(rows.get(0).get("operator_name")).isEqualTo(ADMIN_USERNAME);
  }

  @Test
  @DisplayName("口令错误：401 + 1002，并留 operator_id 为空的 AUTH_LOGIN_FAILED（可溯源被尝试账号）")
  void wrongPasswordIsAuditedWithNullOperator() throws Exception {
    mockMvc
        .perform(json(post("/api/auth/login"), Map.of("username", ADMIN_USERNAME, "password", "wrong-password")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1002));

    List<Map<String, Object>> rows = auditRows("AUTH_LOGIN_FAILED");
    assertThat(rows).isNotEmpty();
    assertThat(rows.get(0).get("operator_id")).isNull();
    assertThat(rows.get(0).get("operator_name")).isEqualTo(ADMIN_USERNAME);
    assertThat(rows.get(0).get("detail")).isNull();
  }

  @Test
  @DisplayName("停用后登录被拒：口令正确也返回 1002（01 A4 出口判据 2）")
  void disabledUserCannotLogin() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long id = createUser(adminToken, "disabled_user", Role.EVALUATOR);

    mockMvc
        .perform(
            authed(
                json(put("/api/users/" + id + "/status"), Map.of("status", "DISABLED", "version", 0)),
                adminToken))
        .andExpect(status().isOk());
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM sys_user WHERE id = ?", String.class, id))
        .isEqualTo("DISABLED");

    mockMvc
        .perform(
            json(post("/api/auth/login"), Map.of("username", "disabled_user", "password", DEFAULT_PASSWORD)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1002));
  }

  @Test
  @DisplayName("越权：MANAGER 访问用户管理 → 403 + 4001，并留 ACCESS_DENIED（已登录才记，10 §E15）")
  void nonAdminIsForbiddenAndAudited() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long managerId = createUser(adminToken, "manager_user", Role.MANAGER);
    String managerToken = login("manager_user", DEFAULT_PASSWORD);

    mockMvc
        .perform(authed(get("/api/users"), managerToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    List<Map<String, Object>> rows = auditRows("ACCESS_DENIED");
    assertThat(rows).isNotEmpty();
    assertThat(rows.get(0).get("operator_id")).isEqualTo(managerId);
    assertThat(rows.get(0).get("operator_name")).isEqualTo("manager_user");
  }

  @Test
  @DisplayName("未登录访问受保护接口：401 + 1001，且不写 ACCESS_DENIED（匿名 401 不留痕）")
  void anonymousRequestIsUnauthorizedWithoutAudit() throws Exception {
    long before = countAudit("ACCESS_DENIED");

    mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(1001));

    assertThat(countAudit("ACCESS_DENIED")).isEqualTo(before);
  }

  @Test
  @DisplayName("用户列表：分页契约 page 从 1 开始、size 生效、total 为总数")
  void userListKeepsPageResultContract() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    createUser(adminToken, "paged_user_1", Role.VIEWER);
    createUser(adminToken, "paged_user_2", Role.VIEWER);

    mockMvc
        .perform(authed(get("/api/users").param("page", "1").param("size", "2"), adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(2))
        .andExpect(jsonPath("$.data.list.length()").value(2))
        .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
  }

  @Test
  @DisplayName("新增用户：账号重复 → 2002（应用层前置校验 + DB 唯一键兜底）")
  void duplicateUsernameIsRejected() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    createUser(adminToken, "dup_user", Role.VIEWER);

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", "dup_user",
                        "password", DEFAULT_PASSWORD,
                        "name", "重复用户",
                        "role", "VIEWER",
                        "department", "OTHER")),
                adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2002));
  }

  @Test
  @DisplayName("启停：版本不符 → 3001；版本正确 → 200 且版本自增（守卫 UPDATE 的真实 SQL）")
  void statusChangeGuardsOnVersion() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long id = createUser(adminToken, "guard_user", Role.VIEWER);

    mockMvc
        .perform(
            authed(
                json(put("/api/users/" + id + "/status"), Map.of("status", "DISABLED", "version", 5)),
                adminToken))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3001));

    mockMvc
        .perform(
            authed(
                json(put("/api/users/" + id + "/status"), Map.of("status", "DISABLED", "version", 0)),
                adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    assertThat(jdbcTemplate.queryForObject("SELECT version FROM sys_user WHERE id = ?", Integer.class, id))
        .isEqualTo(1);

    // 重复提交同一迁移（已是 DISABLED）：0 行 → 3001，而不是静默成功
    mockMvc
        .perform(
            authed(
                json(put("/api/users/" + id + "/status"), Map.of("status", "DISABLED", "version", 1)),
                adminToken))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3001));

    List<Map<String, Object>> rows = auditRows("USER_STATUS_CHANGE");
    assertThat(rows).isNotEmpty();
    assertThat(rows.get(0).get("operator_id")).isEqualTo(userIdOf(ADMIN_USERNAME));
    assertThat(rows.get(0).get("detail")).asString().contains("DISABLED");
  }

  @Test
  @DisplayName("阈值：默认值不落库，保存才写 sys_setting；非法阈值 2005 且不写库")
  void gradeThresholdsDefaultIsNotPersistedUntilSaved() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    assertThat(countSettings()).isZero();
    mockMvc
        .perform(authed(get("/api/settings/grade-thresholds"), adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.a").value(90))
        .andExpect(jsonPath("$.data.b").value(80))
        .andExpect(jsonPath("$.data.c").value(70))
        .andExpect(jsonPath("$.data.d").value(0));
    assertThat(countSettings()).as("读取默认值不应落库（10 §E9）").isZero();

    mockMvc
        .perform(
            authed(
                json(put("/api/settings/grade-thresholds"), Map.of("a", 80, "b", 90, "c", 70, "d", 0)),
                adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2005));
    assertThat(countSettings()).as("非法阈值不得落库（01 §6.3）").isZero();

    mockMvc
        .perform(
            authed(
                json(put("/api/settings/grade-thresholds"), Map.of("a", 90, "b", 90, "c", 70, "d", 0)),
                adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.b").value(90));
    assertThat(countSettings()).as("保存后写一行（upsert）").isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT setting_value FROM sys_setting WHERE setting_key = 'grade.thresholds'", String.class))
        .contains("\"a\": 90")
        .contains("\"b\": 90");

    // 再保存一次走 ON DUPLICATE KEY UPDATE 分支，仍然是同一行
    mockMvc
        .perform(
            authed(
                json(put("/api/settings/grade-thresholds"), Map.of("a", 88, "b", 78, "c", 68, "d", 0)),
                adminToken))
        .andExpect(status().isOk());
    assertThat(countSettings()).isEqualTo(1);

    List<Map<String, Object>> rows = auditRows("SETTING_UPDATE");
    assertThat(rows).isNotEmpty();
    assertThat(rows.get(0).get("target_type")).isEqualTo("SETTING");
  }

  @Test
  @DisplayName("改口令：原口令错 → 2004；新口令过短在校验层被拦（2001）；成功后新口令可登录")
  void selfPasswordChangeFlow() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/auth/password"),
                    Map.of("oldPassword", "not-the-password", "newPassword", "BrandNew1!")),
                adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2004));

    mockMvc
        .perform(
            authed(
                json(put("/api/auth/password"), Map.of("oldPassword", ADMIN_PASSWORD, "newPassword", "short")),
                adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/auth/password"),
                    Map.of("oldPassword", ADMIN_PASSWORD, "newPassword", "Rotated@2026")),
                adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    assertThat(auditRows("USER_PASSWORD_CHANGE")).isNotEmpty();
    // 事务内口令已换：旧口令失效、新口令可用
    mockMvc
        .perform(json(post("/api/auth/login"), Map.of("username", ADMIN_USERNAME, "password", "Rotated@2026")))
        .andExpect(status().isOk());
    mockMvc
        .perform(json(post("/api/auth/login"), Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("UserQueryFacade：findById/isEnabled/findByIds 走真实 SQL（供 P3 校验审批人在职）")
  void userQueryFacadeReadsRealRows() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long id = createUser(adminToken, "facade_user", Role.EVALUATOR);

    assertThat(userQueryFacade.findById(id))
        .isPresent()
        .get()
        .satisfies(
            brief -> {
              assertThat(brief.username()).isEqualTo("facade_user");
              assertThat(brief.role()).isEqualTo(Role.EVALUATOR);
              assertThat(brief.enabled()).isTrue();
            });
    assertThat(userQueryFacade.isEnabled(id)).isTrue();
    assertThat(userQueryFacade.isEnabled(999_999L)).isFalse();
    assertThat(userQueryFacade.findByIds(List.of(id, userIdOf(ADMIN_USERNAME)))).hasSize(2);
    assertThat(userQueryFacade.findByIds(List.of())).isEmpty();
  }

  @Test
  @DisplayName("编辑用户：姓名/角色/部门更新后列表可见，版本自增")
  void updateUserProfile() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long id = createUser(adminToken, "profile_user", Role.VIEWER);

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/users/" + id),
                    Map.of("name", "改名后", "role", "MANAGER", "department", "PURCHASE", "version", 0)),
                adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("改名后"))
        .andExpect(jsonPath("$.data.role").value("MANAGER"))
        .andExpect(jsonPath("$.data.version").value(1));

    assertThat(auditRows("USER_UPDATE")).isNotEmpty();
  }

  @Test
  @DisplayName("重置口令：ADMIN 改他人口令后，本人旧口令失效、新口令可登录")
  void adminResetsAnotherUserPassword() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long id = createUser(adminToken, "reset_target", Role.EVALUATOR);

    mockMvc
        .perform(
            authed(
                json(put("/api/users/" + id + "/password"), Map.of("newPassword", "ResetByAdmin1!")),
                adminToken))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            json(post("/api/auth/login"), Map.of("username", "reset_target", "password", "ResetByAdmin1!")))
        .andExpect(status().isOk());
    assertThat(auditRows("USER_PASSWORD_RESET")).isNotEmpty();
  }

  private long countAudit(String action) {
    Long count =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Long.class, action);
    return count == null ? 0 : count;
  }

  private long countSettings() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_setting WHERE setting_key = 'grade.thresholds'", Long.class);
    return count == null ? 0 : count;
  }
}
