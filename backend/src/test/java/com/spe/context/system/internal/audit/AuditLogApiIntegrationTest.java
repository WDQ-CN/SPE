package com.spe.context.system.internal.audit;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.integration.AbstractIntegrationTest;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 操作日志查询（M5 W5 / 01 SM5）：过滤、分页、时间区间（UTC instant）、动作选项、权限。
 *
 * <p>分享库里的 `audit_log` 还有其它用例/登录留下的行（只增不删，04-041 的同族）⇒ 本类所有查询都带
 * **唯一的操作人前缀** `w5-audit-`，断言只收敛到本用例建立的数据（CLAUDE 默认约定第 5 条）。
 */
@Transactional
class AuditLogApiIntegrationTest extends AbstractIntegrationTest {

  private static final String PROBE = "w5-audit-";

  @Test
  @DisplayName("ADMIN 查询：倒序、动作/目标/操作人过滤、时间区间按 UTC、动作选项")
  void adminCanQueryWithFilters() throws Exception {
    insertAudit("TASK_PUBLISH", "TASK", PROBE + "张三", "{\"suppliers\":3}", LocalDateTime.of(2026, 10, 24, 23, 0));
    insertAudit("TEMPLATE_UPDATE", "TEMPLATE", PROBE + "李四", "{\"name\":\"模板\"}", LocalDateTime.of(2026, 10, 25, 1, 0));
    insertAudit("TASK_PUBLISH", "TASK", PROBE + "李四", "{\"suppliers\":1}", LocalDateTime.of(2026, 10, 25, 2, 0));
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockMvc
        .perform(authed(get("/api/audit-logs").param("operator", PROBE), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(3))
        .andExpect(jsonPath("$.data.list[0].action").value("TASK_PUBLISH"))
        .andExpect(jsonPath("$.data.list[0].detail", containsString("suppliers")))
        .andExpect(jsonPath("$.data.list[1].action").value("TEMPLATE_UPDATE"));

    mockMvc
        .perform(
            authed(
                get("/api/audit-logs").param("operator", PROBE).param("action", "TASK_PUBLISH"),
                admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2));

    mockMvc
        .perform(
            authed(
                get("/api/audit-logs").param("operator", PROBE).param("targetType", "TEMPLATE"),
                admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1));

    mockMvc
        .perform(authed(get("/api/audit-logs").param("operator", PROBE + "李"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2));

    // 2026-10-25T00:00Z..02:00Z（不含右端）覆盖 01:00；23:00 与 02:00 不在内
    mockMvc
        .perform(
            authed(
                get("/api/audit-logs")
                    .param("operator", PROBE)
                    .param("from", "2026-10-25T00:00:00Z")
                    .param("to", "2026-10-25T02:00:00Z"),
                admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1));

    // +08:00 的 10:00 = UTC 02:00：右端不含，仍只有 1 条（证明服务端按 instant 折算）
    mockMvc
        .perform(
            authed(
                get("/api/audit-logs")
                    .param("operator", PROBE)
                    .param("from", "2026-10-25T08:00:00+08:00")
                    .param("to", "2026-10-25T10:00:00+08:00"),
                admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1));

    mockMvc
        .perform(
            authed(
                get("/api/audit-logs").param("operator", PROBE).param("page", "2").param("size", "2"),
                admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(3))
        .andExpect(jsonPath("$.data.list.length()").value(1));

    mockMvc
        .perform(authed(get("/api/audit-logs").param("action", "NO_SUCH"), admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));

    mockMvc
        .perform(authed(get("/api/audit-logs/actions"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasItems("TASK_PUBLISH", "TEMPLATE_UPDATE")));
  }

  @Test
  @DisplayName("V15 索引在位（50k 行 EXPLAIN 证明的两条）")
  void auditIndexesExist() {
    var indexes =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT index_name FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'audit_log'"
                + " ORDER BY index_name",
            String.class);
    org.assertj.core.api.Assertions.assertThat(indexes)
        .as("audit_log 的索引实际=%s", indexes)
        .contains("idx_audit_created", "idx_audit_action_created");
  }

  @Test
  @DisplayName("权限：非 ADMIN 403（含 VIEWER/MANAGER）")
  void onlyAdminCanRead() throws Exception {
    insertUser("w5-audit-viewer", "VIEWER");
    String viewer = login("w5-audit-viewer", "Admin@12345");
    mockMvc.perform(authed(get("/api/audit-logs"), viewer)).andExpect(status().isForbidden());

    insertUser("w5-audit-manager", "MANAGER");
    String manager = login("w5-audit-manager", "Admin@12345");
    mockMvc.perform(authed(get("/api/audit-logs"), manager)).andExpect(status().isForbidden());
  }

  private void insertAudit(
      String action, String targetType, String operator, String detail, LocalDateTime createdAt) {
    jdbcTemplate.update(
        "INSERT INTO audit_log (operator_id, operator_name, action, target_type, target_id, detail, ip, created_at)"
            + " VALUES (NULL, ?, ?, ?, 1, CAST(? AS JSON), NULL, ?)",
        operator,
        action,
        targetType,
        detail,
        createdAt);
  }

  private void insertUser(String username, String role) {
    jdbcTemplate.update(
        "INSERT INTO sys_user (username, password_hash, name, role, department, status, version)"
            + " VALUES (?, ?, ?, ?, 'QUALITY', 'ENABLED', 0)",
        username,
        "$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm",
        "W5 " + username,
        role);
  }
}
