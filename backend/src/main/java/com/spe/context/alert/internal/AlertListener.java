package com.spe.context.alert.internal;

import com.spe.common.event.EventPublisher;
import com.spe.context.alert.api.events.AlertCreatedEvent;
import com.spe.context.evaluation.api.events.TaskSupplierFinalizedEvent;
import com.spe.context.system.api.AlertRules;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.api.SettingsFacade;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 评估单定稿 → 预警判定与落库（01 W2；M5 W2，22 §2 D2）。
 *
 * <p>顺序刻意：**先 supersede 该供应商该周期的活动预警，再按最新结果重算**。这样重评（解锁后再定稿）
 * 不会留下旧结果的预警；`enabled=false` 只关闭新判定，supersede 仍执行（结果已变，旧预警不能继续挂账）。
 *
 * <p>`superseded_by` 只在"新预警里同类型恰好一条"时回填；多条（例如两个关键维度）无法无歧义关联，
 * 留空但状态仍 SUPERSEDED（22 §2 D2）。
 *
 * <p>与 finalize 同一事务：任何一次写库/发事件失败都会让定稿整体回滚（ADR-9）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertListener {

  /** 趋势回溯的上限（8 期规则 + 本期）；与 AlertQueryMapper 的 LIMIT 对齐。 */
  private static final int HISTORY_LIMIT = 12;

  private final AlertMapper alertMapper;
  private final AlertQueryMapper alertQueryMapper;
  private final CorrectiveActionMapper correctiveActionMapper;
  private final SettingsFacade settingsFacade;
  private final AuditFacade auditFacade;
  private final EventPublisher eventPublisher;

  @EventListener
  public void onTaskSupplierFinalized(TaskSupplierFinalizedEvent event) {
    AlertRules rules = settingsFacade.alertRules();
    String supplierName =
        alertQueryMapper
            .findSupplierName(event.supplierId())
            .orElse("供应商 #" + event.supplierId());

    List<Alert> activeBefore = alertMapper.findActive(event.supplierId(), event.year(), event.quarter());
    if (!activeBefore.isEmpty()) {
      alertMapper.supersedeActive(event.supplierId(), event.year(), event.quarter());
      // D7：旧预警被重算取代，未完成整改不能继续针对旧分数推进；已完成的保留历史。
      List<Long> activeIds = activeBefore.stream().map(Alert::getId).toList();
      List<CorrectiveAction> canceledActions =
          correctiveActionMapper.findUnfinishedByAlertIds(activeIds);
      int canceled =
          correctiveActionMapper.cancelUnfinishedByAlertIds(activeIds, "预警被重算取代");
      for (CorrectiveAction action : canceledActions) {
        // 系统动作没有真人操作人；显式留 "SYSTEM" 与原因，审计页才能解释这次取消。
        auditFacade.recordForOperator(
            null,
            "SYSTEM",
            AuditAction.CORRECTIVE_ACTION_CANCEL,
            AuditTargetType.CORRECTIVE_ACTION,
            action.getId(),
            Map.of(
                "alertId", action.getAlertId(),
                "reason", "预警被重算取代",
                "statusBefore", action.getStatus()));
      }
      if (canceled > 0) {
        log.info(
            "供应商 {} {}-Q{} 的预警被取代，取消未完成整改 {} 条",
            event.supplierId(),
            event.year(),
            event.quarter(),
            canceled);
      }
    }
    if (!rules.enabled()) {
      log.info(
          "预警规则已关闭：评估单 {} 的旧预警已 superseded {} 条，不生成新预警",
          event.taskSupplierId(),
          activeBefore.size());
      return;
    }

    Map<Long, String> keyDimensions = new LinkedHashMap<>();
    for (AlertQueryMapper.KeyDimensionRow row :
        alertQueryMapper.findKeyDimensions(event.taskSupplierId())) {
      keyDimensions.put(row.getId(), row.getName());
    }
    List<AlertRuleEngine.DimensionInput> dimensions =
        event.dimensions().stream()
            .map(
                dimension ->
                    new AlertRuleEngine.DimensionInput(
                        dimension.dimensionId(),
                        dimension.dimensionName(),
                        dimension.score(),
                        keyDimensions.containsKey(dimension.dimensionId())))
            .toList();
    List<AlertRuleEngine.PeriodInput> history =
        alertQueryMapper.findRecentEffectivePeriods(event.supplierId(), HISTORY_LIMIT).stream()
            .map(
                row ->
                    new AlertRuleEngine.PeriodInput(
                        row.getYear(), row.getQuarter(), row.getTotalScore()))
            .toList();

    List<AlertRuleEngine.Draft> drafts =
        AlertRuleEngine.evaluate(
            new AlertRuleEngine.Evaluation(
                event.taskSupplierId(),
                event.supplierId(),
                supplierName,
                event.year(),
                event.quarter(),
                event.totalScore(),
                dimensions,
                history,
                event.firstEffective(),
                rules));

    List<Alert> created = new ArrayList<>(drafts.size());
    for (AlertRuleEngine.Draft draft : drafts) {
      Alert alert = new Alert();
      alert.setTaskSupplierId(event.taskSupplierId());
      alert.setSupplierId(event.supplierId());
      alert.setYear(event.year());
      alert.setQuarter(event.quarter());
      alert.setType(draft.type());
      alert.setTitle(draft.title());
      alert.setContent(draft.content());
      alert.setDimensionId(draft.dimensionId());
      alert.setBaselineScore(draft.baselineScore());
      alert.setTargetScore(draft.targetScore());
      alert.setStatus("UNREAD");
      alertMapper.insert(alert);
      created.add(alert);
    }

    for (Alert old : activeBefore) {
      List<Alert> sameType =
          created.stream().filter(alert -> alert.getType().equals(old.getType())).toList();
      if (sameType.size() == 1) {
        alertMapper.linkSupersededBy(old.getId(), sameType.get(0).getId());
      }
    }

    for (Alert alert : created) {
      eventPublisher.publish(
          new AlertCreatedEvent(
              alert.getId(),
              alert.getTaskSupplierId(),
              alert.getSupplierId(),
              supplierName,
              alert.getYear(),
              alert.getQuarter(),
              alert.getType(),
              alert.getTitle(),
              alert.getContent()));
    }

    log.info(
        "评估单 {} 定稿预警：新增 {} 条，superseded {} 条（供应商 {} {}-Q{}）",
        event.taskSupplierId(),
        created.size(),
        activeBefore.size(),
        event.supplierId(),
        event.year(),
        event.quarter());
  }
}
