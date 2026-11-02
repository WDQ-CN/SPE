package com.spe.context.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.spe.context.notify.internal.NotificationService;
import com.spe.integration.AbstractIntegrationTest;
import com.spe.integration.support.TestFixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** W4：整改创建/验证就绪的站内通知（alert 事件 → notify 监听器）。 */
@Transactional
class CorrectiveActionNotificationIntegrationTest extends AbstractIntegrationTest {

  @Autowired private CorrectiveActionService service;
  @Autowired private CorrectiveActionVerifier verifier;

  @Test
  @DisplayName("创建整改：只给负责人写一条站内通知，内容含供应商/编号/截止日期")
  void createNotifiesOwner() {
    long supplierId = TestFixtures.insertSupplier(jdbcTemplate, "W4-NOTICE-SUP", "通知供应商");
    long managerId = TestFixtures.insertUser(jdbcTemplate, "w4-notice-mgr", "MANAGER");
    long ownerId = TestFixtures.insertUser(jdbcTemplate, "w4-notice-owner", "EVALUATOR");
    long alertId = insertAlert(supplierId);

    CorrectiveAction action =
        service.create(
            alertId,
            managerId,
            true,
            ownerId,
            LocalDate.of(2026, 12, 31),
            "通知测试整改措施",
            null);

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT receiver_id, type, title, content FROM notification"
                + " WHERE type = ? AND receiver_id = ? ORDER BY id DESC LIMIT 1",
            NotificationService.TYPE_CORRECTIVE_ACTION_CREATED,
            ownerId);
    assertThat(row.get("title")).isEqualTo("整改任务已指派");
    assertThat(row.get("content").toString())
        .contains("通知供应商", "#" + action.getId(), "2026-12-31");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE type = ? AND receiver_id = ?",
                Integer.class,
                NotificationService.TYPE_CORRECTIVE_ACTION_CREATED,
                managerId))
        .isZero();
  }

  @Test
  @DisplayName("下期验证回填：负责人与创建人各一条；同一人时去重")
  void verificationReadyNotifiesOwnerAndCreator() {
    long supplierId = TestFixtures.insertSupplier(jdbcTemplate, "W4-NOTICE-VERIFY", "验证通知供应商");
    long ownerId = TestFixtures.insertUser(jdbcTemplate, "w4-notice-verify-owner", "EVALUATOR");
    long creatorId = TestFixtures.insertUser(jdbcTemplate, "w4-notice-verify-creator", "MANAGER");
    long actionId = insertAction(supplierId, ownerId, creatorId);
    insertEffectiveNextPeriod(supplierId, 99001L, 99011L);

    verifier.fillForSupplier(supplierId);

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT receiver_id, title, content FROM notification"
                + " WHERE type = ? ORDER BY receiver_id",
            NotificationService.TYPE_CORRECTIVE_VERIFY_READY);
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .extracting(row -> ((Number) row.get("receiver_id")).longValue())
        .containsExactlyInAnyOrder(ownerId, creatorId);
    assertThat(rows.get(0).get("title")).isEqualTo("整改验证就绪");
    assertThat(rows.get(0).get("content").toString())
        .contains("#" + actionId, "88.60", "已改善");
  }

  @Test
  @DisplayName("验证就绪：负责人=创建人时只写一条通知")
  void verificationReadyDeduplicatesSameReceiver() {
    long supplierId = TestFixtures.insertSupplier(jdbcTemplate, "W4-NOTICE-SAME", "同一人供应商");
    long sameId = TestFixtures.insertUser(jdbcTemplate, "w4-notice-same", "MANAGER");
    insertAction(supplierId, sameId, sameId);
    insertEffectiveNextPeriod(supplierId, 99002L, 99012L);

    verifier.fillForSupplier(supplierId);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE type = ? AND receiver_id = ?",
                Integer.class,
                NotificationService.TYPE_CORRECTIVE_VERIFY_READY,
                sameId))
        .isEqualTo(1);
  }

  private long insertAlert(long supplierId) {
    jdbcTemplate.update(
        "INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content,"
            + " baseline_score, target_score, status)"
            + " VALUES (?, ?, 2026, 3, 'TOTAL_LOW', '总分低于阈值', '通知测试', 68.00, 70.00, 'UNREAD')",
        98001L,
        supplierId);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM alert WHERE task_supplier_id = 98001", Long.class);
  }

  private long insertAction(long supplierId, long ownerId, long createdBy) {
    jdbcTemplate.update(
        "INSERT INTO corrective_action"
            + " (alert_id, task_supplier_id, supplier_id, baseline_year, baseline_quarter,"
            + "  baseline_score, target_score, owner_id, due_date, measure, status, created_by)"
            + " VALUES (?, ?, ?, 2026, 3, 68.00, 70.00, ?, '2026-12-31', '通知测试整改',"
            + "         'WAITING_VERIFICATION', ?)",
        98101L + supplierId,
        98111L + supplierId,
        supplierId,
        ownerId,
        createdBy);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM corrective_action WHERE supplier_id = ? ORDER BY id DESC LIMIT 1",
        Long.class,
        supplierId);
  }

  private void insertEffectiveNextPeriod(long supplierId, long taskId, long taskSupplierId) {
    jdbcTemplate.update(
        "INSERT INTO eval_task (id, name, year, quarter, template_version_id, deadline, status, version)"
            + " VALUES (?, ?, 2026, 4, 1, '2026-12-31', 'COMPLETED', 0)",
        taskId,
        "W4 通知验证任务 " + taskId);
    jdbcTemplate.update(
        "INSERT INTO task_supplier (id, task_id, supplier_id, status, current_node, total_score,"
            + " total_grade, version) VALUES (?, ?, ?, 'EFFECTIVE', 0, 88.60, 'A', 0)",
        taskSupplierId,
        taskId,
        supplierId);
  }
}
