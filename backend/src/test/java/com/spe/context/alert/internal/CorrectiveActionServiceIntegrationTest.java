package com.spe.context.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.events.TaskSupplierFinalizedEvent;
import com.spe.integration.AbstractIntegrationTest;
import com.spe.integration.support.TestFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/** W2：创建整改、状态机、权限/版本守卫与旧预警降级（D8）。 */
@Transactional
class CorrectiveActionServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private CorrectiveActionService service;
  @Autowired private CorrectiveActionMapper mapper;
  @Autowired private ApplicationEventPublisher eventPublisher;

  @Test
  @DisplayName("从预警创建整改：同事务 HANDLED、版本递增、可开始/提交/验证")
  void createAndWalkStateMachine() {
    long managerId = TestFixtures.insertUser(jdbcTemplate, "w2-action-manager", "MANAGER");
    long ownerId = TestFixtures.insertUser(jdbcTemplate, "w2-action-owner", "EVALUATOR");
    long alertId = insertAlert("UNREAD", "68.00", "70.00", 96001L, 96002L);

    CorrectiveAction created =
        service.create(
            alertId,
            managerId,
            true,
            ownerId,
            LocalDate.of(2026, 12, 31),
            "  改进来料检验流程  ",
            "下期合格率不低于 98%");
    assertThat(created.getStatus()).isEqualTo("OPEN");
    assertThat(created.getVersion()).isZero();
    assertThat(created.getMeasure()).isEqualTo("改进来料检验流程");
    assertThat(created.getBaselineScore()).isEqualByComparingTo("68.00");
    assertThat(created.getTargetScore()).isEqualByComparingTo("70.00");
    assertThat(statusOfAlert(alertId)).isEqualTo("HANDLED");

    CorrectiveAction started =
        service.start(created.getId(), ownerId, false, created.getVersion());
    assertThat(started.getStatus()).isEqualTo("IN_PROGRESS");
    assertThat(started.getVersion()).isEqualTo(1);

    CorrectiveAction submitted =
        service.submit(started.getId(), ownerId, false, "已完成检验流程调整", started.getVersion());
    assertThat(submitted.getStatus()).isEqualTo("WAITING_VERIFICATION");
    assertThat(submitted.getVersion()).isEqualTo(2);

    CorrectiveAction verified =
        service.verify(submitted.getId(), managerId, true, true, "下期报告已核对", submitted.getVersion());
    assertThat(verified.getStatus()).isEqualTo("VERIFIED");
    assertThat(verified.getVerifiedBy()).isEqualTo(managerId);
    assertThat(verified.getVersion()).isEqualTo(3);
  }

  @Test
  @DisplayName("重复创建、旧预警、非法负责人都拒绝；创建不满足条件时预警仍为 UNREAD")
  void creationGuards() {
    long managerId = TestFixtures.insertUser(jdbcTemplate, "w2-action-manager2", "MANAGER");
    long ownerId = TestFixtures.insertUser(jdbcTemplate, "w2-action-owner2", "EVALUATOR");
    long viewerId = TestFixtures.insertUser(jdbcTemplate, "w2-action-viewer", "VIEWER");
    long alertId = insertAlert("UNREAD", "68.00", "70.00", 96011L, 96012L);

    service.create(
        alertId,
        managerId,
        true,
        ownerId,
        LocalDate.of(2026, 12, 31),
        "第一次整改",
        null);
    assertThatThrownBy(
            () ->
                service.create(
                    alertId,
                    managerId,
                    true,
                    ownerId,
                    LocalDate.of(2026, 12, 31),
                    "重复整改",
                    null))
        .isInstanceOf(BizException.class)
        .extracting(error -> ((BizException) error).errorCode())
        .isEqualTo(ErrorCode.ALERT_NOT_ACTIONABLE);

    long legacyAlert = insertAlert("UNREAD", null, null, 96013L, 96014L);
    assertThatThrownBy(
            () ->
                service.create(
                    legacyAlert,
                    managerId,
                    true,
                    ownerId,
                    LocalDate.of(2026, 12, 31),
                    "旧预警整改",
                    null))
        .isInstanceOf(BizException.class)
        .extracting(error -> ((BizException) error).errorCode())
        .isEqualTo(ErrorCode.ALERT_NOT_ACTIONABLE);
    assertThat(statusOfAlert(legacyAlert)).isEqualTo("UNREAD");

    long newAlert = insertAlert("UNREAD", "68.00", "70.00", 96015L, 96016L);
    assertThatThrownBy(
            () ->
                service.create(
                    newAlert,
                    managerId,
                    true,
                    viewerId,
                    LocalDate.of(2026, 12, 31),
                    "错误负责人",
                    null))
        .isInstanceOf(BizException.class)
        .extracting(error -> ((BizException) error).errorCode())
        .isEqualTo(ErrorCode.CORRECTIVE_ACTION_OWNER_INVALID);
  }

  @Test
  @DisplayName("非负责人不能推进；旧 version 返回 3001")
  void permissionAndVersionGuards() {
    long managerId = TestFixtures.insertUser(jdbcTemplate, "w2-action-manager3", "MANAGER");
    long ownerId = TestFixtures.insertUser(jdbcTemplate, "w2-action-owner3", "EVALUATOR");
    long otherId = TestFixtures.insertUser(jdbcTemplate, "w2-action-other", "EVALUATOR");
    long alertId = insertAlert("UNREAD", "68.00", "70.00", 96021L, 96022L);
    CorrectiveAction action =
        service.create(
            alertId,
            managerId,
            true,
            ownerId,
            LocalDate.of(2026, 12, 31),
            "措施",
            null);

    assertThatThrownBy(() -> service.start(action.getId(), otherId, false, action.getVersion()))
        .isInstanceOf(BizException.class)
        .extracting(error -> ((BizException) error).errorCode())
        .isEqualTo(ErrorCode.FORBIDDEN);

    CorrectiveAction started =
        service.start(action.getId(), ownerId, false, action.getVersion());
    assertThatThrownBy(() -> service.start(action.getId(), ownerId, false, action.getVersion()))
        .isInstanceOf(BizException.class)
        .extracting(error -> ((BizException) error).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    assertThat(started.getVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName("D7/P2：定稿先 supersede 再验证；被取消的整改不留下期回填")
  void supersedeCancelsAndAudits() {
    long supplierId = TestFixtures.insertSupplier(jdbcTemplate, "W2-D7-SUP");
    long managerId = TestFixtures.insertUser(jdbcTemplate, "w2-d7-manager", "MANAGER");
    long ownerId = TestFixtures.insertUser(jdbcTemplate, "w2-d7-owner", "EVALUATOR");
    jdbcTemplate.update(
        "INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content,"
            + " dimension_id, baseline_score, target_score, status)"
            + " VALUES (?, ?, ?, ?, 'TOTAL_LOW', '旧预警', '旧内容', NULL, 60.00, 70.00, 'UNREAD')",
        97001L,
        supplierId,
        2026,
        1);
    long alertId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM alert WHERE task_supplier_id = 97001", Long.class);
    jdbcTemplate.update(
        "INSERT INTO corrective_action"
            + " (alert_id, task_supplier_id, supplier_id, baseline_year, baseline_quarter,"
            + "  baseline_score, target_score, owner_id, due_date, measure, status, created_by)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WAITING_VERIFICATION', ?)",
        alertId,
        97001L,
        supplierId,
        2026,
        1,
        60.00,
        70.00,
        ownerId,
        "2026-06-30",
        "旧整改措施",
        managerId);
    // 如果事件顺序错误，验证器会先看到这条 WAITING 整改并回填 Q2；正确顺序应先被 D7 取消。
    jdbcTemplate.update(
        "INSERT INTO eval_task (id, name, year, quarter, template_version_id, deadline, status, version)"
            + " VALUES (97002, 'W2 D7 下期任务', 2026, 2, 1, '2026-12-31', 'COMPLETED', 0)");
    jdbcTemplate.update(
        "INSERT INTO task_supplier (id, task_id, supplier_id, status, current_node, total_score,"
            + " total_grade, version) VALUES (97012, 97002, ?, 'EFFECTIVE', 0, 88.00, 'B', 0)",
        supplierId);

    eventPublisher.publishEvent(
        new TaskSupplierFinalizedEvent(
            97001L,
            supplierId,
            2026,
            1,
            new BigDecimal("50.00"),
            "D",
            List.of(),
            false));

    assertThat(statusOfAlert(alertId)).isEqualTo("SUPERSEDED");
    Map<String, Object> action =
        jdbcTemplate.queryForMap(
            "SELECT status, canceled_reason, verify_year, version FROM corrective_action WHERE alert_id = ?",
            alertId);
    assertThat(action.get("status")).isEqualTo("CANCELED");
    assertThat(action.get("canceled_reason")).isEqualTo("预警被重算取代");
    assertThat(action.get("verify_year")).as("已取消的整改不应被验证器回填").isNull();
    assertThat(((Number) action.get("version")).intValue()).isEqualTo(1);

    Map<String, Object> audit =
        jdbcTemplate.queryForMap(
            "SELECT operator_id, operator_name, target_type, target_id, detail FROM audit_log"
                + " WHERE action = 'CORRECTIVE_ACTION_CANCEL'");
    assertThat(audit.get("operator_id")).isNull();
    assertThat(audit.get("operator_name")).isEqualTo("SYSTEM");
    assertThat(audit.get("target_type")).isEqualTo("CORRECTIVE_ACTION");
    assertThat(audit.get("detail").toString())
        .contains("alertId", "\"reason\"", "预警被重算取代");
  }

  private long insertAlert(
      String status, String baselineScore, String targetScore, long taskSupplierId, long alertIdMarker) {
    jdbcTemplate.update(
        "INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content,"
            + " dimension_id, baseline_score, target_score, status)"
            + " VALUES (?, ?, ?, ?, 'TOTAL_LOW', '总分低于阈值', '测试预警', NULL, ?, ?, ?)",
        taskSupplierId,
        alertIdMarker,
        2026,
        3,
        baselineScore,
        targetScore,
        status);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM alert WHERE task_supplier_id = ? ORDER BY id DESC LIMIT 1",
        Long.class,
        taskSupplierId);
  }

  private String statusOfAlert(long alertId) {
    return jdbcTemplate.queryForObject("SELECT status FROM alert WHERE id = ?", String.class, alertId);
  }
}
