package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M1 验收：审计留痕项核对（08 的 P1 验收「第一个 AuditFacade 留痕项核对」）。
 *
 * <p>与其它集成用例的区别是**它是清单式的**：一次走遍 P1 的全部写路径，然后按
 * 06 §2.8 的动作清单逐条断言「这一条动作确实留痕、且内容正确」，最后检查审计里没有口令与令牌。
 * 分散用例各自验证自己那条路径的业务结果，这里回答的是另一个问题：
 * **有没有哪条写路径压根没留痕**——那种缺口在单条用例里看不出来。
 *
 * <p>因此它与分散用例的部分重复是**有意的**：这是一份可重复执行的验收清单，
 * 将来新增写路径时，往 {@link #P1_AUDIT_ACTIONS} 里加一个动作名就该能跑红。
 */
class AuditTrailAcceptanceTest extends AbstractIntegrationTest {

  /** 06 §2.8 在 P1 范围内登记的全部动作（13 个）。 */
  private static final List<String> P1_AUDIT_ACTIONS =
      List.of(
          "AUTH_LOGIN_SUCCESS",
          "AUTH_LOGIN_FAILED",
          "AUTH_LOGIN_BLOCKED",
          "USER_CREATE",
          "USER_UPDATE",
          "USER_STATUS_CHANGE",
          "USER_PASSWORD_RESET",
          "USER_PASSWORD_CHANGE",
          "SETTING_UPDATE",
          "SUPPLIER_CREATE",
          "SUPPLIER_UPDATE",
          "SUPPLIER_STATUS_CHANGE",
          "ACCESS_DENIED");

  /** 限流探针：连续失败到阈值会锁 15 分钟，用它而不是 admin（03 §17 的教训）。 */
  private static final String LIMIT_PROBE = "audit-trail-limit-probe";

  @Test
  @DisplayName("P1 的每条写路径都留痕：13 个动作齐备、内容正确、detail 无口令与令牌")
  void everyWritePathLeavesAnAuditRow() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    failedLogin(FAILURE_PROBE_ACCOUNT, "definitely-wrong").andExpect(status().isUnauthorized());

    // ---- 用户：增 → 改 → 重置口令 → 本人改口令 → 停用 ----
    long userId = createUser(adminToken, "audit_trail_user", "EVALUATOR");
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/users/" + userId),
                    Map.of("name", "验收改名", "role", "MANAGER", "department", "PURCHASE", "version", 0)),
                adminToken))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            authed(
                json(put("/api/users/" + userId + "/password"), Map.of("newPassword", "ResetByAudit1!")),
                adminToken))
        .andExpect(status().isOk());
    // 先重置再让本人改口令（改口令需要该账号可登录，所以停用放在最后）
    String userToken = login("audit_trail_user", "ResetByAudit1!");
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/auth/password"),
                    Map.of("oldPassword", "ResetByAudit1!", "newPassword", "ChangedByAudit1!")),
                userToken))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/users/" + userId + "/status"),
                    Map.of("status", "DISABLED", "version", userVersion(userId))),
                adminToken))
        .andExpect(status().isOk());

    // ---- 阈值 ----
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/settings/grade-thresholds"),
                    Map.of("a", 90, "b", 80, "c", 70, "d", 0)),
                adminToken))
        .andExpect(status().isOk());

    // ---- 供应商：增 → 改 → 停用 ----
    long supplierId = createSupplier(adminToken, "AUDIT-TRAIL-001");
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/suppliers/" + supplierId),
                    Map.of("code", "AUDIT-TRAIL-001", "name", "验收改名供应商", "category", "电子", "version", 0)),
                adminToken))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/suppliers/" + supplierId + "/status"),
                    Map.of("status", "DISABLED", "version", supplierVersion(supplierId))),
                adminToken))
        .andExpect(status().isOk());

    // ---- 越权：用 EVALUATOR 的令牌访问仅 ADMIN 的接口 ----
    long viewerId = createUser(adminToken, "audit_trail_viewer", "EVALUATOR");
    String viewerToken = login("audit_trail_viewer", DEFAULT_PASSWORD);
    mockMvc
        .perform(authed(get("/api/users").param("page", "1").param("size", "10"), viewerToken))
        .andExpect(status().isForbidden());

    // ---- 登录限流：连续失败到阈值，进入锁定 ----
    for (int attempt = 0; attempt < 5; attempt++) {
      failedLogin(LIMIT_PROBE, "definitely-wrong").andExpect(status().isUnauthorized());
    }

    // ---- 逐条核对：13 个动作都要有行 ----
    for (String action : P1_AUDIT_ACTIONS) {
      assertThat(auditRows(action)).as("动作 %s 应当留痕", action).isNotEmpty();
    }

    // ---- 内容正确性：操作人、目标、IP ----
    // 注意不能取 auditRows(...).get(0)：那是"最新一条"，而本用例中途还有别的账号登录，
    // 最新一条属于最后登录的那个账号。这里要断言的是「存在一条 admin 的登录成功记录」。
    assertThat(auditRows("AUTH_LOGIN_SUCCESS"))
        .as("admin 的登录成功应当留痕，且带来源 IP")
        .anySatisfy(
            row -> {
              assertThat(row.get("operator_id")).isEqualTo(userIdOf(ADMIN_USERNAME));
              assertThat(row.get("ip")).isNotNull();
            });

    Map<String, Object> userUpdate =
        jdbcTemplate.queryForMap(
            "SELECT operator_id, target_type, target_id FROM audit_log"
                + " WHERE action = 'USER_UPDATE' AND target_id = ? ORDER BY id DESC LIMIT 1",
            userId);
    assertThat(userUpdate.get("operator_id")).isEqualTo(userIdOf(ADMIN_USERNAME));
    assertThat(userUpdate.get("target_type")).isEqualTo("USER");

    Map<String, Object> supplierUpdate =
        jdbcTemplate.queryForMap(
            "SELECT operator_id, target_type, target_id FROM audit_log"
                + " WHERE action = 'SUPPLIER_UPDATE' AND target_id = ? ORDER BY id DESC LIMIT 1",
            supplierId);
    assertThat(supplierUpdate.get("target_type")).isEqualTo("SUPPLIER");

    Map<String, Object> denied = auditRows("ACCESS_DENIED").get(0);
    assertThat(denied.get("operator_id")).isEqualTo(viewerId);

    Map<String, Object> blocked = auditRows("AUTH_LOGIN_BLOCKED").get(0);
    assertThat(blocked.get("operator_name")).isEqualTo(LIMIT_PROBE);
    assertThat(blocked.get("operator_id")).as("锁定发生在认证之前").isNull();

    // ---- 敏感信息：审计里不得出现口令或令牌 ----
    Long leaked =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE LOWER(COALESCE(detail, ''))"
                + " REGEXP 'password|passwd|pwd|token|secret|bearer|admin@12345'",
            Long.class);
    assertThat(leaked).as("审计 detail 不得含口令/令牌（06 §8 第 11 条）").isZero();
  }

  /**
   * 现查乐观锁版本，而不是在请求里写死数字。
   *
   * <p>多步流程里 version 会一路累积——重置口令同样是一次守卫 UPDATE（version+1），
   * 于是「创建后 version=0、编辑后 1、再停用应该传 1」这种推算迟早过时，结果是 409 而非失败得明明白白。
   * 本用例关心的是「有没有留痕」，不是「守卫版本对不对」（后者由 SystemApiIntegrationTest 覆盖）。
   */
  private int userVersion(long userId) {
    Integer version =
        jdbcTemplate.queryForObject("SELECT version FROM sys_user WHERE id = ?", Integer.class, userId);
    return version == null ? 0 : version;
  }

  private int supplierVersion(long supplierId) {
    Integer version =
        jdbcTemplate.queryForObject("SELECT version FROM supplier WHERE id = ?", Integer.class, supplierId);
    return version == null ? 0 : version;
  }

  private long createUser(String adminToken, String username, String role) throws Exception {
    String body =
        mockMvc
            .perform(
                authed(
                    json(
                        post("/api/users"),
                        Map.of(
                            "username", username,
                            "password", DEFAULT_PASSWORD,
                            "name", "验收账号",
                            "role", role,
                            "department", "QUALITY")),
                    adminToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).path("data").path("id").asLong();
  }

  private long createSupplier(String adminToken, String code) throws Exception {
    String body =
        mockMvc
            .perform(
                authed(
                    json(
                        post("/api/suppliers"),
                        Map.of("code", code, "name", "验收供应商", "category", "电子")),
                    adminToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).path("data").path("id").asLong();
  }
}
