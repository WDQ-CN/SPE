package com.spe.context.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.spe.integration.AbstractIntegrationTest;
import com.spe.integration.support.TestFixtures;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** W2：D5 下期自动取分与同周期重复的 PENDING 降级。 */
@Transactional
class CorrectiveActionVerifierIntegrationTest extends AbstractIntegrationTest {

  @Autowired private CorrectiveActionVerifier verifier;
  @Autowired private CorrectiveActionService service;

  @Test
  @DisplayName("D5：唯一候选回填实际分；重开提交后以下一次验证周期重新取数")
  void fillsUniqueNextPeriod() {
    long supplierId = TestFixtures.insertSupplier(jdbcTemplate, "W2-VERIFY-SUP");
    insertTask(95001L, 2026, 4);
    insertTaskSupplier(95011L, 95001L, supplierId, 88.60);
    long actionId = insertAction(supplierId, 95011L);

    verifier.fillForSupplier(supplierId);

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT verify_year, verify_quarter, verify_task_supplier_id, verify_score,"
                + " verify_outcome, verify_comment, version FROM corrective_action WHERE id = ?",
            actionId);
    assertThat(row.get("verify_year")).isEqualTo(2026);
    assertThat(row.get("verify_quarter")).isEqualTo(4);
    assertThat(((Number) row.get("verify_task_supplier_id")).longValue()).isEqualTo(95011L);
    assertThat(row.get("verify_score").toString()).isEqualTo("88.60");
    assertThat(row.get("verify_outcome")).isEqualTo("IMPROVED");
    assertThat(row.get("verify_comment")).isNull();
    assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);

    // P1：经理判「继续整改」后重开、负责人重提；验证器应以 Q4 为基线取 2027 Q1，
    // 而不是因为 verify_year 已存在就永远跳过。
    jdbcTemplate.update(
        "UPDATE corrective_action SET status = 'WAITING_VERIFICATION',"
            + " verified_by = 701, verified_at = CURRENT_TIMESTAMP(3),"
            + " verify_outcome = 'NOT_IMPROVED', verify_comment = '继续整改' WHERE id = ?",
        actionId);
    insertTask(95004L, 2027, 1);
    insertTaskSupplier(95014L, 95004L, supplierId, 91.00);

    verifier.fillForSupplier(supplierId);

    Map<String, Object> reverified =
        jdbcTemplate.queryForMap(
            "SELECT verify_year, verify_quarter, verify_task_supplier_id, verify_score,"
                + " verify_outcome, verify_comment, verified_by, verified_at, version"
                + " FROM corrective_action WHERE id = ?",
            actionId);
    assertThat(reverified.get("verify_year")).isEqualTo(2027);
    assertThat(reverified.get("verify_quarter")).isEqualTo(1);
    assertThat(((Number) reverified.get("verify_task_supplier_id")).longValue())
        .isEqualTo(95014L);
    assertThat(reverified.get("verify_score").toString()).isEqualTo("91.00");
    assertThat(reverified.get("verify_outcome")).isEqualTo("IMPROVED");
    assertThat(reverified.get("verify_comment")).isNull();
    assertThat(reverified.get("verified_by")).isNull();
    assertThat(reverified.get("verified_at")).isNull();
    assertThat(((Number) reverified.get("version")).intValue()).isEqualTo(2);

    // P2：新周期回填清空 verified_at 后，经理可以再次验证通过。
    CorrectiveAction verified =
        service.verify(actionId, 701L, true, true, "重开后再验证通过", 2);
    assertThat(verified.getStatus()).isEqualTo("VERIFIED");
    assertThat(verified.getVersion()).isEqualTo(3);
  }

  @Test
  @DisplayName("D5：同周期多份 EFFECTIVE → PENDING、不绑定任一评估单、留人工意见")
  void duplicatePeriodBecomesPending() {
    long supplierId = TestFixtures.insertSupplier(jdbcTemplate, "W2-VERIFY-DUP");
    insertTask(95002L, 2026, 4);
    insertTask(95003L, 2026, 4);
    insertTaskSupplier(95012L, 95002L, supplierId, 88.60);
    insertTaskSupplier(95013L, 95003L, supplierId, 87.20);
    long actionId = insertAction(supplierId, 95011L);

    verifier.fillForSupplier(supplierId);

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT verify_year, verify_quarter, verify_task_supplier_id, verify_score,"
                + " verify_outcome, verify_comment FROM corrective_action WHERE id = ?",
            actionId);
    assertThat(row.get("verify_year")).isEqualTo(2026);
    assertThat(row.get("verify_quarter")).isEqualTo(4);
    assertThat(row.get("verify_task_supplier_id")).isNull();
    assertThat(row.get("verify_score")).isNull();
    assertThat(row.get("verify_outcome")).isEqualTo("PENDING");
    assertThat(row.get("verify_comment").toString()).contains("同周期存在多份");
  }

  private long insertAction(long supplierId, long taskSupplierId) {
    jdbcTemplate.update(
        "INSERT INTO corrective_action"
            + " (alert_id, task_supplier_id, supplier_id, baseline_year, baseline_quarter,"
            + "  baseline_score, target_score, owner_id, due_date, measure, status, created_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WAITING_VERIFICATION', ?)",
        95101L + taskSupplierId,
        taskSupplierId,
        supplierId,
        2026,
        3,
        68.00,
        70.00,
        701L,
        "2026-12-31",
        "验证测试整改",
        701L);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM corrective_action WHERE supplier_id = ? ORDER BY id DESC LIMIT 1",
        Long.class,
        supplierId);
  }

  private void insertTask(long id, int year, int quarter) {
    jdbcTemplate.update(
        "INSERT INTO eval_task (id, name, year, quarter, template_version_id, deadline, status, version)"
            + " VALUES (?, ?, ?, ?, 1, '2026-12-31', 'COMPLETED', 0)",
        id,
        "W2 验证任务 " + id,
        year,
        quarter);
  }

  private void insertTaskSupplier(long id, long taskId, long supplierId, double totalScore) {
    jdbcTemplate.update(
        "INSERT INTO task_supplier (id, task_id, supplier_id, status, current_node, total_score,"
            + " total_grade, version) VALUES (?, ?, ?, 'EFFECTIVE', 0, ?, 'B', 0)",
        id,
        taskId,
        supplierId,
        totalScore);
  }
}
