package com.spe.context.alert.internal;

import com.spe.common.event.EventPublisher;
import com.spe.context.alert.api.events.CorrectiveActionVerificationReadyEvent;
import com.spe.context.evaluation.api.events.TaskSupplierFinalizedEvent;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 下期验证取数（D5/D9；W2）。
 *
 * <p>只在定稿事务内读 `EFFECTIVE` 快照：总分取 `task_supplier.total_score`，维度分取
 * `task_supplier_dimension_result`，不读试算。整改列表按供应商+基线周期查询候选，
 * 不把“最早晚于基线”的选择藏在 SQL 的 LIMIT 1 里。
 *
 * <p>同周期多份 EFFECTIVE（J4 无唯一约束）或下期缺同一维度时，写 `PENDING` 并留验证意见，
 * 由经理人工判断；绝不静默挑一份分数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorrectiveActionVerifier {

  private final CorrectiveActionMapper correctiveActionMapper;
  private final AlertQueryMapper alertQueryMapper;
  private final EventPublisher eventPublisher;

  /**
   * 定稿事件到达后回填等待中的整改。`@Order(20)` 与 `AlertListener` 的 `@Order(10)` 配合：
   * 先 supersede/取消未完成整改，再给仍然 WAITING 的行取数，避免给刚被取消的整改回填。
   *
   * <p>两者都在定稿事务内，任何写库失败都会让定稿回滚。
   */
  @EventListener
  @Order(20)
  public void onTaskSupplierFinalized(TaskSupplierFinalizedEvent event) {
    fillForSupplier(event.supplierId());
  }

  /** 负责人提交时，如果下期已经存在，立即补做一次只读取数，不用等到下一次定稿。 */
  public void fillIfAvailable(long actionId) {
    CorrectiveAction action = correctiveActionMapper.selectById(actionId);
    if (action == null
        || !CorrectiveActionStatus.WAITING_VERIFICATION.name().equals(action.getStatus())) {
      return;
    }
    boolean reverify = isReverify(action);
    if (action.getVerifyYear() != null && !reverify) {
      return;
    }
    fillOne(action, reverify);
  }

  public void fillForSupplier(long supplierId) {
    List<CorrectiveAction> waiting =
        correctiveActionMapper.findWaitingVerificationBySupplier(supplierId);
    for (CorrectiveAction action : waiting) {
      fillOne(action, isReverify(action));
    }
  }

  /**
   * 「继续整改」后重新提交：上次已经由经理确认过（`verified_at` 非空），但状态又回到 WAITING。
   *
   * <p>此时以**上次验证周期**为基线继续往后取，否则会一直重复第一次的周期；
   * 首次等待经理确认（`verified_at` 为空）不算，避免后续定稿覆盖尚未确认的结果。
   */
  private static boolean isReverify(CorrectiveAction action) {
    return CorrectiveActionSummaryView.isReverifyPending(
        action.getStatus(), action.getVerifiedAt(), action.getVerifyYear());
  }

  private void fillOne(CorrectiveAction action, boolean reverify) {
    int baselineYear = reverify ? action.getVerifyYear() : action.getBaselineYear();
    int baselineQuarter = reverify ? action.getVerifyQuarter() : action.getBaselineQuarter();
    List<NextEffectivePeriodRow> candidates =
        correctiveActionMapper.findEffectivePeriodsAfter(
            action.getSupplierId(), baselineYear, baselineQuarter);
    if (candidates.isEmpty()) {
      return;
    }
    NextEffectivePeriodRow earliest = candidates.get(0);
    long samePeriod =
        candidates.stream()
            .filter(
                row ->
                    row.getYear().equals(earliest.getYear())
                        && row.getQuarter().equals(earliest.getQuarter()))
            .count();
    if (samePeriod > 1) {
      fill(
          action,
          earliest,
          null,
          null,
          "PENDING",
          "同周期存在多份生效评估，需人工选择后再验证",
          reverify);
      return;
    }

    BigDecimal actual =
        action.getDimensionId() == null
            ? earliest.getTotalScore()
            : correctiveActionMapper.findDimensionScore(
                earliest.getTaskSupplierId(), action.getDimensionId());
    if (actual == null) {
      fill(action, earliest, null, null, "PENDING", "下期缺少对应维度分，需人工判断", reverify);
      return;
    }
    String outcome =
        actual.compareTo(action.getTargetScore()) >= 0 ? "IMPROVED" : "NOT_IMPROVED";
    fill(action, earliest, earliest.getTaskSupplierId(), actual, outcome, null, reverify);
  }

  private void fill(
      CorrectiveAction action,
      NextEffectivePeriodRow period,
      Long taskSupplierId,
      BigDecimal score,
      String outcome,
      String comment,
      boolean reverify) {
    int updated =
        correctiveActionMapper.fillVerification(
            action.getId(),
            action.getVersion(),
            period.getYear(),
            period.getQuarter(),
            taskSupplierId,
            score,
            outcome,
            comment,
            reverify);
    if (updated == 0) {
      log.info("整改 {} 的验证数据已被其他请求写入或状态已变化，跳过", action.getId());
      return;
    }
    log.info(
        "整改 {} 回填下期 {}-Q{}：score={}, outcome={}, reverify={}",
        action.getId(),
        period.getYear(),
        period.getQuarter(),
        score,
        outcome,
        reverify);
    String supplierName =
        alertQueryMapper
            .findSupplierName(action.getSupplierId())
            .orElse("供应商 #" + action.getSupplierId());
    eventPublisher.publish(
        new CorrectiveActionVerificationReadyEvent(
            action.getId(),
            action.getAlertId(),
            action.getSupplierId(),
            supplierName,
            action.getOwnerId(),
            action.getCreatedBy(),
            period.getYear(),
            period.getQuarter(),
            score,
            outcome));
  }
}
