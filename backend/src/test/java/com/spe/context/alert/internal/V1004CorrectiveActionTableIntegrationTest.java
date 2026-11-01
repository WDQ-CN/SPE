package com.spe.context.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.integration.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

/**
 * W1（V1004）：alert 结构化列、corrective_action 表、唯一键与 CHECK 必须由数据库兜底。
 *
 * <p>沿用 V14 测试的判据：断言 information_schema 的实际值，而不是只看 Service 代码。
 * `@Transactional` 让测试行回滚，避免污染共享库。
 */
@Transactional
class V1004CorrectiveActionTableIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("V1004：alert 三列可空、整改表默认 OPEN/version=0、四个索引在位")
  void schemaIsApplied() {
    List<String> alertColumns =
        jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = 'alert'"
                + " AND column_name IN ('dimension_id', 'baseline_score', 'target_score')"
                + " ORDER BY column_name",
            String.class);
    assertThat(alertColumns)
        .as("alert 的结构化列实际=%s", alertColumns)
        .containsExactly("baseline_score", "dimension_id", "target_score");

    jdbcTemplate.update(
        "INSERT INTO corrective_action"
            + " (alert_id, task_supplier_id, supplier_id, baseline_year, baseline_quarter,"
            + "  baseline_score, target_score, owner_id, due_date, measure, created_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        93001L,
        93002L,
        93003L,
        2026,
        3,
        68.00,
        70.00,
        701L,
        "2026-12-31",
        "测试整改措施",
        701L);

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT status, version, verify_outcome FROM corrective_action WHERE alert_id = ?",
            93001L);
    assertThat(row.get("status")).as("默认状态，实际=%s", row).isEqualTo("OPEN");
    assertThat(((Number) row.get("version")).intValue()).isZero();
    assertThat(row.get("verify_outcome")).isNull();

    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT index_name FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'corrective_action'"
                + " ORDER BY index_name",
            String.class);
    assertThat(indexes)
        .as("整改表索引实际=%s", indexes)
        .contains(
            "uk_action_alert",
            "idx_action_owner_status_due",
            "idx_action_supplier_period",
            "idx_action_status_created");
  }

  @Test
  @DisplayName("V1004：重复 alert_id 被唯一键拒绝，非法季度被 CHECK 拒绝")
  void constraintsAreEnforced() {
    insertAction(93101L, 3);
    assertThatThrownBy(() -> insertAction(93101L, 3))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uk_action_alert");
    assertThatThrownBy(() -> insertAction(93102L, 5))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_action_quarter");
  }

  private void insertAction(long alertId, int quarter) {
    jdbcTemplate.update(
        "INSERT INTO corrective_action"
            + " (alert_id, task_supplier_id, supplier_id, baseline_year, baseline_quarter,"
            + "  baseline_score, target_score, owner_id, due_date, measure, created_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        alertId,
        93102L,
        93103L,
        2026,
        quarter,
        68.00,
        70.00,
        701L,
        "2026-12-31",
        "测试整改措施",
        701L);
  }
}
