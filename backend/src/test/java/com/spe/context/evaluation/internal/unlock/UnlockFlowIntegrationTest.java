package com.spe.context.evaluation.internal.unlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员解锁重评（M5 W3 / 01 R5）：状态回退、轮次 +1、评分单解锁、快照删除、任务回退、
 * 审计、事件（旧预警 superseded + 通知指派人）、权限与守卫。
 */
@Transactional
class UnlockFlowIntegrationTest extends AbstractIntegrationTest {

  @Autowired private UnlockService unlockService;

  @Test
  @DisplayName("ADMIN 解锁：状态/轮次/分数/评分单/快照/任务/审计/事件全部到位")
  void adminUnlockRollsBackTheWholeResult() throws Exception {
    long supplierId = insertSupplier("W3-UNLOCK-SUP");
    long evaluatorId = insertUser("w3-unlock-eval", "EVALUATOR");
    long versionId = defaultVersionId();
    List<Long> dimensionIds = dimensionIds(versionId);
    long taskId = insertTask("W3 解锁任务", 2026, 3, "COMPLETED", versionId);
    long tsId = insertTaskSupplier(taskId, supplierId, "80.00", "B");
    insertSheets(tsId, dimensionIds, evaluatorId);
    insertSnapshots(tsId, dimensionIds);
    insertAlert(supplierId, 2026, 3);

    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    mockMvc
        .perform(authed(json(post("/api/task-suppliers/" + tsId + "/unlock"), unlockBody(0)), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    Map<String, Object> ts =
        jdbcTemplate.queryForMap(
            "SELECT status, current_node, round, total_score, total_grade, version"
                + " FROM task_supplier WHERE id = ?",
            tsId);
    assertThat(ts.get("status")).isEqualTo("SCORING");
    assertThat(((Number) ts.get("current_node")).intValue()).isZero();
    assertThat(((Number) ts.get("round")).intValue()).as("重审必须开新轮次").isEqualTo(2);
    assertThat(ts.get("total_score")).isNull();
    assertThat(ts.get("total_grade")).isNull();
    assertThat(((Number) ts.get("version")).intValue()).isEqualTo(1);

    assertThat(
            jdbcTemplate.queryForList(
                "SELECT status, version FROM sheet WHERE task_supplier_id = ?", tsId))
        .as("评分单全部解锁")
        .allSatisfy(
            row -> {
              assertThat(row.get("status")).isEqualTo("SCORING");
              assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);
            });
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_supplier_dimension_result WHERE task_supplier_id = ?",
                Long.class,
                tsId))
        .as("维度快照必须删除（否则重定稿撞 uk_ts_dimension）")
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM eval_task WHERE id = ?", String.class, taskId))
        .as("已完成任务回退进行中")
        .isEqualTo("IN_PROGRESS");

    Map<String, Object> audit =
        jdbcTemplate.queryForMap(
            "SELECT target_type, target_id, detail FROM audit_log"
                + " WHERE action = 'TASK_SUPPLIER_UNLOCK' ORDER BY id DESC LIMIT 1");
    assertThat(audit.get("target_type")).isEqualTo("TASK");
    assertThat(((Number) audit.get("target_id")).longValue()).isEqualTo(tsId);
    var detail = objectMapper.readTree(audit.get("detail").toString());
    assertThat(detail.path("round").asInt()).isEqualTo(1);
    assertThat(detail.path("comment").asText()).contains("复核分数");
    assertThat(detail.path("beforeTotalScore").decimalValue()).isEqualByComparingTo("80.00");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM alert WHERE supplier_id = ? AND year = 2026 AND quarter = 3",
                String.class,
                supplierId))
        .as("解锁后旧预警 superseded")
        .isEqualTo("SUPERSEDED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE receiver_id = ?"
                    + " AND type = 'TASK_SUPPLIER_UNLOCKED'",
                Long.class,
                evaluatorId))
        .as("指派人收到重评通知")
        .isEqualTo(1);

    // 第二次（未重新定稿）→ 2020
    assertThatThrownBy(() -> unlockService.unlock(tsId, 1, null))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TASK_STATUS_INVALID);
  }

  @Test
  @DisplayName("任务已进行中但评估单已生效：ADMIN 仍可解锁，任务保持进行中")
  void unlockEffectiveResultOnInProgressTask() {
    long supplierId = insertSupplier("W3-INPROGRESS-SUP");
    long evaluatorId = insertUser("w3-inprogress-eval", "EVALUATOR");
    long versionId = defaultVersionId();
    List<Long> dimensionIds = dimensionIds(versionId);
    long taskId = insertTask("W3 进行中解锁任务", 2026, 2, "IN_PROGRESS", versionId);
    long tsId = insertTaskSupplier(taskId, supplierId, "80.00", "B");
    insertSheets(tsId, dimensionIds, evaluatorId);
    insertSnapshots(tsId, dimensionIds);

    unlockService.unlock(tsId, 0, null);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM task_supplier WHERE id = ?", String.class, tsId))
        .isEqualTo("SCORING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM eval_task WHERE id = ?", String.class, taskId))
        .as("本来就在进行中，不改任务状态")
        .isEqualTo("IN_PROGRESS");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_supplier_dimension_result WHERE task_supplier_id = ?",
                Long.class,
                tsId))
        .isZero();
  }

  @Test
  @DisplayName("守卫：非 ADMIN 403；非 EFFECTIVE 2020；已取消任务 2020；版本不符 3001；不存在 2028")
  void guardsAreEnforced() throws Exception {
    long supplierId = insertSupplier("W3-GUARD-SUP");
    long evaluatorId = insertUser("w3-guard-eval", "EVALUATOR");
    long versionId = defaultVersionId();
    List<Long> dimensionIds = dimensionIds(versionId);

    long activeTask = insertTask("W3 守卫任务", 2026, 4, "IN_PROGRESS", versionId);
    long effectiveTs = insertTaskSupplier(activeTask, supplierId, "80.00", "B");
    insertSheets(effectiveTs, dimensionIds, evaluatorId);
    insertSnapshots(effectiveTs, dimensionIds);
    long scoringTask = insertTask("W3 评分中任务", 2025, 4, "IN_PROGRESS", versionId);
    long scoringTs = insertTaskSupplier(scoringTask, supplierId, null, null);

    insertUser("w3-guard-manager", "MANAGER");
    String manager = login("w3-guard-manager", "Admin@12345");
    mockMvc
        .perform(
            authed(json(post("/api/task-suppliers/" + effectiveTs + "/unlock"), unlockBody(0)), manager))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    assertCode(() -> unlockService.unlock(effectiveTs, 99, null), ErrorCode.CONCURRENT_CONFLICT);
    assertCode(() -> unlockService.unlock(scoringTs, 0, null), ErrorCode.TASK_STATUS_INVALID);
    assertCode(() -> unlockService.unlock(99999999L, 0, null), ErrorCode.TASK_SUPPLIER_NOT_FOUND);

    long canceledTask = insertTask("W3 取消任务", 2025, 3, "CANCELED", versionId);
    long canceledTs = insertTaskSupplier(canceledTask, supplierId, "70.00", "C");
    assertCode(() -> unlockService.unlock(canceledTs, 0, null), ErrorCode.TASK_STATUS_INVALID);
  }

  private void assertCode(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(expected);
  }

  private Map<String, Object> unlockBody(int version) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", version);
    body.put("comment", "复核分数");
    return body;
  }

  private long insertSupplier(String code) {
    jdbcTemplate.update(
        "INSERT INTO supplier (code, name, category, status, version)"
            + " VALUES (?, ?, 'W3 解锁', 'ENABLED', 0)",
        code,
        "供应商 " + code);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM supplier WHERE code = ?", Long.class, code);
  }

  private long insertUser(String username, String role) {
    jdbcTemplate.update(
        "INSERT INTO sys_user (username, password_hash, name, role, department, status, version)"
            + " VALUES (?, ?, ?, ?, 'QUALITY', 'ENABLED', 0)",
        username,
        "$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm",
        "W3 " + username,
        role);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
  }

  private long insertTask(String name, int year, int quarter, String status, long versionId) {
    jdbcTemplate.update(
        "INSERT INTO eval_task (name, year, quarter, template_version_id, deadline, status, version)"
            + " VALUES (?, ?, ?, ?, ?, ?, 0)",
        name,
        year,
        quarter,
        versionId,
        LocalDate.of(year, 12, 31),
        status);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM eval_task WHERE name = ?", Long.class, name);
  }

  private long insertTaskSupplier(long taskId, long supplierId, String total, String grade) {
    jdbcTemplate.update(
        "INSERT INTO task_supplier (task_id, supplier_id, status, current_node, round,"
            + " total_score, total_grade, version)"
            + " VALUES (?, ?, ?, 0, 1, ?, ?, 0)",
        taskId,
        supplierId,
        total == null ? "SCORING" : "EFFECTIVE",
        total == null ? null : new BigDecimal(total),
        grade);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM task_supplier WHERE task_id = ? AND supplier_id = ?",
        Long.class,
        taskId,
        supplierId);
  }

  private void insertSheets(long tsId, List<Long> dimensionIds, long assigneeId) {
    for (Long dimensionId : dimensionIds) {
      jdbcTemplate.update(
          "INSERT INTO sheet (task_supplier_id, dimension_id, assignee_id, status, version)"
              + " VALUES (?, ?, ?, 'SUBMITTED', 0)",
          tsId,
          dimensionId,
          assigneeId);
    }
  }

  private void insertSnapshots(long tsId, List<Long> dimensionIds) {
    for (Long dimensionId : dimensionIds) {
      jdbcTemplate.update(
          "INSERT INTO task_supplier_dimension_result"
              + " (task_supplier_id, dimension_id, dimension_name, weight, score, grade)"
              + " VALUES (?, ?, ?, 25.00, 80.00, 'B')",
          tsId,
          dimensionId,
          "维度" + dimensionId);
    }
  }

  private void insertAlert(long supplierId, int year, int quarter) {
    jdbcTemplate.update(
        "INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content)"
            + " VALUES (90001, ?, ?, ?, 'TOTAL_LOW', '总分低于阈值', '测试')",
        supplierId,
        year,
        quarter);
  }

  private List<Long> dimensionIds(long versionId) {
    return jdbcTemplate.queryForList(
        "SELECT id FROM dimension WHERE template_version_id = ? ORDER BY sort_no, id LIMIT 2",
        Long.class,
        versionId);
  }

  private long defaultVersionId() {
    return jdbcTemplate.queryForObject(
        "SELECT v.id FROM template_version v JOIN template t ON t.id = v.template_id"
            + " WHERE t.name = '季度绩效默认模板' AND v.version_no = 1 AND v.status = 'PUBLISHED'",
        Long.class);
  }
}
