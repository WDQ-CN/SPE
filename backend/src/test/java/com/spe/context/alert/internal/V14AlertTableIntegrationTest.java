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
 * M5 W1（22 §42 判据 D1）：V14 的 alert 表**可写可读**、默认值正确、季度约束会拦、索引真实存在
 * （含 V1002 给分页列表加的 `idx_alert_created`）。
 *
 * <p>断言外部协议（information_schema）时打印实际值 —— 04-062 的教训。
 * 本类标 `@Transactional` 让测试行回滚（共享库，04-041/092 的教训）。
 */
@Transactional
class V14AlertTableIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("W1 V14 alert：默认 UNREAD、可写满、季度约束拦截、两条索引在位")
  void alertTableRoundTripAndConstraints() {
    jdbcTemplate.update(
        "INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        91001L,
        81001L,
        2026,
        2,
        "TOTAL_LOW",
        "总分低于阈值",
        "供应商 DEMO-001 2026 Q2 总分 62.00 低于阈值 70");

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT status, handled_by, handled_comment, handled_at, superseded_by, created_at"
                + " FROM alert WHERE task_supplier_id = ?",
            91001L);
    assertThat(row.get("status")).as("默认状态，实际=%s", row).isEqualTo("UNREAD");
    assertThat(row.get("handled_by")).isNull();
    assertThat(row.get("handled_comment")).isNull();
    assertThat(row.get("handled_at")).isNull();
    assertThat(row.get("superseded_by")).isNull();
    assertThat(row.get("created_at")).as("created_at 必须落库").isNotNull();

    // 处理与取代字段可写回
    jdbcTemplate.update(
        "UPDATE alert SET status = 'HANDLED', handled_by = ?, handled_comment = ?,"
            + " handled_at = CURRENT_TIMESTAMP(3), superseded_by = NULL"
            + " WHERE task_supplier_id = ?",
        701L,
        "已约谈供应商",
        91001L);
    Map<String, Object> handled =
        jdbcTemplate.queryForMap(
            "SELECT status, handled_by, handled_comment, handled_at FROM alert WHERE task_supplier_id = ?",
            91001L);
    assertThat(handled.get("status")).isEqualTo("HANDLED");
    assertThat(((Number) handled.get("handled_by")).longValue()).isEqualTo(701L);
    assertThat(handled.get("handled_comment")).isEqualTo("已约谈供应商");
    assertThat(handled.get("handled_at")).isNotNull();

    // 季度约束（CHECK）必须由数据库兜底
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    91002L,
                    81002L,
                    2026,
                    5,
                    "TOTAL_LOW",
                    "非法季度",
                    "quarter=5 应被 CHECK 拦下"))
        // MySQL 的 CHECK 违反是 error 3819，Spring 不做细分翻译 ⇒ 断言父类 + 约束名，比绑死具体异常更稳
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("ck_alert_quarter");

    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT index_name FROM information_schema.statistics"
                + " WHERE table_schema = DATABASE() AND table_name = 'alert' ORDER BY index_name",
            String.class);
    assertThat(indexes)
        .as("alert 的索引实际=%s", indexes)
        .contains(
            "idx_alert_status_created",
            "idx_alert_supplier_period_type",
            // V1002（11 §9.4 的探针结论）：分页列表的排序索引，AlertMapper.findPage 依赖它
            "idx_alert_created");
  }
}
