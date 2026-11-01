package com.spe.context.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.spe.integration.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** W1：D5 下期候选与 D7 supersede 批量取消的 SQL 语义。 */
@Transactional
class CorrectiveActionMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired private CorrectiveActionMapper mapper;

  @Test
  @DisplayName("D5：返回晚于基线的全部候选，按周期/id 排序并暴露同周期重复")
  void findsAllEffectivePeriodsAfterBaseline() {
    insertTask(94001L, 2026, 4);
    insertTask(94002L, 2027, 1);
    insertTask(94003L, 2026, 4);
    insertTaskSupplier(94011L, 94001L, 94003L, 88.60);
    insertTaskSupplier(94012L, 94002L, 94003L, 91.00);
    insertTaskSupplier(94013L, 94003L, 94003L, 87.20);

    List<NextEffectivePeriodRow> rows = mapper.findEffectivePeriodsAfter(94003L, 2026, 3);

    assertThat(rows).as("基线 2026 Q3 的两个晚于候选周期").hasSize(3);
    assertThat(rows.get(0).getYear()).isEqualTo(2026);
    assertThat(rows.get(0).getQuarter()).isEqualTo(4);
    assertThat(rows.get(0).getTaskSupplierId()).isEqualTo(94011L);
    assertThat(rows.get(0).getTotalScore()).isEqualByComparingTo("88.60");
    assertThat(rows.get(1).getTaskSupplierId()).as("同周期第二份不能被静默丢弃").isEqualTo(94013L);
    assertThat(rows.get(2).getYear()).isEqualTo(2027);
  }

  @Test
  @DisplayName("D7：只取消未完成整改，VERIFIED 保留且每条 version+1")
  void cancelsOnlyUnfinishedActions() {
    insertAction(94101L, "OPEN");
    insertAction(94102L, "VERIFIED");
    insertAction(94103L, "WAITING_VERIFICATION");

    int changed = mapper.cancelUnfinishedByAlertIds(List.of(94101L, 94102L, 94103L), "预警被重算取代");

    assertThat(changed).as("OPEN + WAITING 两条被取消").isEqualTo(2);
    assertThat(statusOf(94101L)).isEqualTo("CANCELED");
    assertThat(statusOf(94102L)).isEqualTo("VERIFIED");
    assertThat(statusOf(94103L)).isEqualTo("CANCELED");
    assertThat(versionOf(94101L)).as("取消也要递增版本，防止旧详情继续写").isEqualTo(1);
    assertThat(versionOf(94102L)).isZero();
  }

  private void insertTask(long id, int year, int quarter) {
    jdbcTemplate.update(
        "INSERT INTO eval_task (id, name, year, quarter, template_version_id, deadline, status, version)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        "W1 整改测试任务 " + id,
        year,
        quarter,
        1L,
        "2026-12-31",
        "COMPLETED",
        0);
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

  private void insertAction(long alertId, String status) {
    jdbcTemplate.update(
        "INSERT INTO corrective_action"
            + " (alert_id, task_supplier_id, supplier_id, baseline_year, baseline_quarter,"
            + "  baseline_score, target_score, owner_id, due_date, measure, status, created_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        alertId,
        94011L,
        94003L,
        2026,
        3,
        68.00,
        70.00,
        701L,
        "2026-12-31",
        "测试整改措施 " + alertId,
        status,
        701L);
  }

  private String statusOf(long alertId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM corrective_action WHERE alert_id = ?", String.class, alertId);
  }

  private int versionOf(long alertId) {
    return jdbcTemplate.queryForObject(
        "SELECT version FROM corrective_action WHERE alert_id = ?", Integer.class, alertId);
  }
}
