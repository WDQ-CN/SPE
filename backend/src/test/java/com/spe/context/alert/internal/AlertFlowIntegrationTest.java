package com.spe.context.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.spe.common.event.EventPublisher;
import com.spe.context.evaluation.api.events.TaskSupplierFinalizedEvent;
import com.spe.context.evaluation.api.events.TaskSupplierUnlockedEvent;
import com.spe.context.system.api.AlertRules;
import com.spe.context.system.internal.setting.SettingService;
import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预警流程（M5 W2/W3 的事件链）：定稿 → 三条规则 → 通知经理；重定稿 → supersede/重算；
 * 解锁 → supersede；规则关闭 → 不新建但仍 supersede。
 *
 * <p>用真实事件发布（`EventPublisher`）驱动监听器，与生产同一条链路；测试事务回滚，故事件与
 * 通知都不落库。缓存不随事务回滚 ⇒ `@AfterEach` 清缓存。
 */
@Transactional
class AlertFlowIntegrationTest extends AbstractIntegrationTest {

  @Autowired private EventPublisher eventPublisher;

  @Autowired private SettingService settingService;

  @Autowired private CacheManager cacheManager;

  @AfterEach
  void clearCaches() {
    // 缓存是进程内单例、不随事务回滚（04-041 的同族）：逐条清（不引用 system 的包内常量，跨上下文只依赖 api）
    for (String name : cacheManager.getCacheNames()) {
      var cache = cacheManager.getCache(name);
      if (cache != null) {
        cache.clear();
      }
    }
  }

  @Test
  @DisplayName("定稿：总分/关键维度/连续下降三条预警 + 经理通知；重定稿 supersede；解锁 supersede")
  void finalizeCreatesAlertsAndUnlockSupersedes() {
    long supplierId = insertSupplier("W2-ALERT-SUP");
    long managerId = insertUser("w2-flow-manager");
    long versionId = defaultVersionId();
    List<Map<String, Object>> dimensions =
        jdbcTemplate.queryForList(
            "SELECT id, name, weight FROM dimension WHERE template_version_id = ?"
                + " ORDER BY sort_no, id",
            versionId);
    long keyDimensionId = ((Number) dimensions.get(0).get("id")).longValue();
    // 默认模板的关键维度全是 0；测试内临时把第一个维度标成关键（事务回滚）
    jdbcTemplate.update("UPDATE dimension SET is_key = 1 WHERE id = ?", keyDimensionId);

    long currentTask = insertTask("W2 流程 2026Q2", 2026, 2, "IN_PROGRESS", versionId);
    long currentTs = insertTaskSupplier(currentTask, supplierId, "65.00", "D");
    long q1 = insertTask("W2 流程 2026Q1", 2026, 1, "COMPLETED", versionId);
    insertTaskSupplier(q1, supplierId, "85.00", "B");
    long q4 = insertTask("W2 流程 2025Q4", 2025, 4, "COMPLETED", versionId);
    insertTaskSupplier(q4, supplierId, "90.00", "A");

    publishFinalized(currentTs, supplierId, 2026, 2, dimensions, "65.00", "65.00", "95.00", true);

    List<Map<String, Object>> alerts =
        jdbcTemplate.queryForList(
            "SELECT type, status, title, content FROM alert WHERE task_supplier_id = ? ORDER BY id",
            currentTs);
    assertThat(alerts).extracting(row -> row.get("type"))
        .containsExactlyInAnyOrder("TOTAL_LOW", "KEY_DIMENSION_LOW", "TREND_DECLINE");
    assertThat(alerts).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("UNREAD"));
    assertThat(alerts)
        .filteredOn(row -> "TREND_DECLINE".equals(row.get("type")))
        .singleElement()
        .satisfies(row -> assertThat(row.get("content").toString()).contains("累计下降 25"));

    Long notifications =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notification WHERE receiver_id = ? AND type = 'ALERT_CREATED'",
            Long.class,
            managerId);
    assertThat(notifications).as("每条预警给经理 1 条通知").isEqualTo(3);

    // 重定稿（解锁后）：关键维度回到 95 ⇒ 只剩 TOTAL；趋势只在首次生效时生成，重定稿不再出现。
    // 旧的三条全部 SUPERSEDED，同类型回填 superseded_by，未再命中的留空。
    publishFinalized(currentTs, supplierId, 2026, 2, dimensions, "65.00", "95.00", "95.00", false);

    List<Map<String, Object>> oldOnes =
        jdbcTemplate.queryForList(
            "SELECT id, type, status, superseded_by FROM alert WHERE task_supplier_id = ?"
                + " ORDER BY id",
            currentTs);
    assertThat(oldOnes).hasSize(4);
    List<Map<String, Object>> superseded =
        oldOnes.stream().filter(row -> "SUPERSEDED".equals(row.get("status"))).toList();
    assertThat(superseded).hasSize(3);
    Map<String, Object> newTotal =
        oldOnes.stream()
            .filter(row -> "UNREAD".equals(row.get("status")))
            .filter(row -> "TOTAL_LOW".equals(row.get("type")))
            .findFirst()
            .orElseThrow();
    Map<String, Object> oldTotal =
        oldOnes.stream()
            .filter(row -> "SUPERSEDED".equals(row.get("status")))
            .filter(row -> "TOTAL_LOW".equals(row.get("type")))
            .findFirst()
            .orElseThrow();
    assertThat(oldTotal.get("superseded_by"))
        .as("同类型恰好一条 ⇒ 回填新记录 id")
        .isEqualTo(newTotal.get("id"));
    Map<String, Object> oldKey =
        oldOnes.stream()
            .filter(row -> "SUPERSEDED".equals(row.get("status")))
            .filter(row -> "KEY_DIMENSION_LOW".equals(row.get("type")))
            .findFirst()
            .orElseThrow();
    assertThat(oldKey.get("superseded_by"))
        .as("新结果里没有同类型 ⇒ 留空")
        .isNull();
    Map<String, Object> oldTrend =
        oldOnes.stream()
            .filter(row -> "SUPERSEDED".equals(row.get("status")))
            .filter(row -> "TREND_DECLINE".equals(row.get("type")))
            .findFirst()
            .orElseThrow();
    assertThat(oldTrend.get("superseded_by"))
        .as("重定稿不再生成趋势预警（01 W2 首次生效）")
        .isNull();

    // 解锁：活动预警全部 SUPERSEDED
    eventPublisher.publish(
        new TaskSupplierUnlockedEvent(currentTs, supplierId, 2026, 2, 2, List.of()));
    Long active =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM alert WHERE task_supplier_id = ?"
                + " AND status IN ('UNREAD','HANDLED')",
            Long.class,
            currentTs);
    assertThat(active).as("解锁后不保留活动预警").isZero();
  }

  @Test
  @DisplayName("规则关闭：不生成新预警，但仍把旧活动预警 supersede")
  void disabledRulesStillSupersede() {
    long supplierId = insertSupplier("W2-OFF-SUP");
    insertUser("w2-off-manager");
    long versionId = defaultVersionId();
    long taskId = insertTask("W2 关闭 2026Q2", 2026, 2, "IN_PROGRESS", versionId);
    long tsId = insertTaskSupplier(taskId, supplierId, "60.00", "D");
    List<Map<String, Object>> dimensions =
        jdbcTemplate.queryForList(
            "SELECT id, name, weight FROM dimension WHERE template_version_id = ?"
                + " ORDER BY sort_no, id",
            versionId);

    publishFinalized(tsId, supplierId, 2026, 2, dimensions, "60.00", "60.00", "95.00", true);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert WHERE task_supplier_id = ?", Long.class, tsId))
        .isEqualTo(1);

    settingService.updateAlertRules(new AlertRules(false, 70, 70, 2, 5));
    publishFinalized(tsId, supplierId, 2026, 2, dimensions, "60.00", "60.00", "95.00", false);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert WHERE task_supplier_id = ?", Long.class, tsId))
        .as("规则关闭不新建")
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM alert WHERE task_supplier_id = ?", String.class, tsId))
        .as("旧预警仍须 supersede（结果已重算）")
        .isEqualTo("SUPERSEDED");
  }

  @Test
  @DisplayName("两个关键维度同时命中：旧预警的 superseded_by 留空（新同类记录不唯一）")
  void multipleKeyDimensionsDoNotLinkAmbiguously() {
    long supplierId = insertSupplier("W2-ALERT-MULTI");
    insertUser("w2-multi-manager");
    long versionId = defaultVersionId();
    List<Map<String, Object>> dimensions =
        jdbcTemplate.queryForList(
            "SELECT id, name, weight FROM dimension WHERE template_version_id = ?"
                + " ORDER BY sort_no, id",
            versionId);
    long first = ((Number) dimensions.get(0).get("id")).longValue();
    long second = ((Number) dimensions.get(1).get("id")).longValue();
    jdbcTemplate.update("UPDATE dimension SET is_key = 1 WHERE id IN (?, ?)", first, second);

    long taskId = insertTask("W2 多关键维度", 2026, 2, "IN_PROGRESS", versionId);
    long tsId = insertTaskSupplier(taskId, supplierId, "90.00", "A");
    publishFinalized(tsId, supplierId, 2026, 2, dimensions, "90.00", "60.00", "60.00", true);
    publishFinalized(tsId, supplierId, 2026, 2, dimensions, "90.00", "60.00", "60.00", false);

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT type, status, superseded_by FROM alert WHERE task_supplier_id = ? ORDER BY id",
            tsId);
    List<Map<String, Object>> superseded =
        rows.stream().filter(row -> "SUPERSEDED".equals(row.get("status"))).toList();
    assertThat(superseded).hasSize(2);
    assertThat(superseded)
        .allSatisfy(row -> assertThat(row.get("superseded_by")).as("同类型新记录有两条 ⇒ 无法无歧义关联").isNull());
    assertThat(rows)
        .filteredOn(row -> "KEY_DIMENSION_LOW".equals(row.get("type")))
        .filteredOn(row -> "UNREAD".equals(row.get("status")))
        .hasSize(2);
  }

  private void publishFinalized(
      long tsId,
      long supplierId,
      int year,
      int quarter,
      List<Map<String, Object>> dimensions,
      String total,
      String firstDimensionScore,
      String secondDimensionScore,
      boolean firstEffective) {
    List<TaskSupplierFinalizedEvent.DimensionResult> results =
        List.of(
            new TaskSupplierFinalizedEvent.DimensionResult(
                ((Number) dimensions.get(0).get("id")).longValue(),
                (String) dimensions.get(0).get("name"),
                (BigDecimal) dimensions.get(0).get("weight"),
                new BigDecimal(firstDimensionScore),
                "A"),
            new TaskSupplierFinalizedEvent.DimensionResult(
                ((Number) dimensions.get(1).get("id")).longValue(),
                (String) dimensions.get(1).get("name"),
                (BigDecimal) dimensions.get(1).get("weight"),
                new BigDecimal(secondDimensionScore),
                "A"));
    eventPublisher.publish(
        new TaskSupplierFinalizedEvent(
            tsId,
            supplierId,
            year,
            quarter,
            new BigDecimal(total),
            "D",
            results,
            firstEffective));
  }

  private long insertSupplier(String code) {
    jdbcTemplate.update(
        "INSERT INTO supplier (code, name, category, status, version)"
            + " VALUES (?, ?, 'W2 预警', 'ENABLED', 0)",
        code,
        "供应商 " + code);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM supplier WHERE code = ?", Long.class, code);
  }

  private long insertUser(String username) {
    jdbcTemplate.update(
        "INSERT INTO sys_user (username, password_hash, name, role, department, status, version)"
            + " VALUES (?, ?, ?, 'MANAGER', 'PURCHASE', 'ENABLED', 0)",
        username,
        "$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm",
        "W2 " + username);
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
            + " total_score, total_grade, version) VALUES (?, ?, 'EFFECTIVE', 0, 1, ?, ?, 0)",
        taskId,
        supplierId,
        new BigDecimal(total),
        grade);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM task_supplier WHERE task_id = ? AND supplier_id = ?",
        Long.class,
        taskId,
        supplierId);
  }

  private long defaultVersionId() {
    return jdbcTemplate.queryForObject(
        "SELECT v.id FROM template_version v JOIN template t ON t.id = v.template_id"
            + " WHERE t.name = '季度绩效默认模板' AND v.version_no = 1 AND v.status = 'PUBLISHED'",
        Long.class);
  }
}
