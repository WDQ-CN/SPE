package com.spe.context.evaluation.internal.unlock;

import com.spe.common.event.EventPublisher;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.EvalTaskStatus;
import com.spe.context.evaluation.api.TaskSupplierStatus;
import com.spe.context.evaluation.api.events.TaskSupplierUnlockedEvent;
import com.spe.context.evaluation.internal.approval.TaskSupplierDimensionResultMapper;
import com.spe.context.evaluation.internal.sheet.SheetMapper;
import com.spe.context.evaluation.internal.task.EvalTask;
import com.spe.context.evaluation.internal.task.EvalTaskMapper;
import com.spe.context.evaluation.internal.task.TaskSupplier;
import com.spe.context.evaluation.internal.task.TaskSupplierMapper;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员解锁重评（01 R5 / 05 §5.5 T4；M5 W3）。
 *
 * <p>单事务编排六件事：守卫迁移（EFFECTIVE → SCORING、round+1、清总分）、评分单解锁、
 * **删除维度快照**（否则重定稿撞 `uk_ts_dimension`）、任务回退进行中、审计、发布事件。
 *
 * <p>顺序刻意：先 `lockById`（当前读兼作串行化点），再读任务与判状态——后来者会看到已回退的状态而返回
 * 2020，不会重复删快照。`version` 由调用方从报告带来（乐观锁）。
 *
 * <p>「不可连续解锁两次」由 `status='EFFECTIVE'` 守卫自然保证：第一次成功后状态已回 SCORING，
 * 第二次 2020；重定稿（再次 EFFECTIVE）后允许再解锁——中间已有一次定稿，不算连续。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnlockService {

  private static final int COMMENT_MAX_LENGTH = 500;

  private final TaskSupplierMapper taskSupplierMapper;
  private final EvalTaskMapper taskMapper;
  private final SheetMapper sheetMapper;
  private final TaskSupplierDimensionResultMapper dimensionResultMapper;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  @Transactional
  public void unlock(long taskSupplierId, int version, String comment) {
    String normalizedComment = comment == null ? null : comment.trim();
    if (normalizedComment != null && normalizedComment.isEmpty()) {
      normalizedComment = null;
    }
    if (normalizedComment != null && normalizedComment.length() > COMMENT_MAX_LENGTH) {
      throw new BizException(ErrorCode.BAD_REQUEST, "解锁意见长度不得超过 500 字");
    }

    TaskSupplier taskSupplier =
        taskSupplierMapper
            .lockById(taskSupplierId)
            .orElseThrow(() -> new BizException(ErrorCode.TASK_SUPPLIER_NOT_FOUND));
    EvalTask task =
        taskMapper
            .findById(taskSupplier.getTaskId())
            .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    if (task.getStatus() != EvalTaskStatus.IN_PROGRESS
        && task.getStatus() != EvalTaskStatus.COMPLETED) {
      throw new BizException(
          ErrorCode.TASK_STATUS_INVALID, "任务当前状态不允许解锁：" + task.getStatus());
    }
    if (taskSupplier.getStatus() != TaskSupplierStatus.EFFECTIVE) {
      throw new BizException(
          ErrorCode.TASK_STATUS_INVALID, "只有已生效的评估单可以解锁，当前状态：" + taskSupplier.getStatus());
    }
    if (taskSupplier.getVersion() != version) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    int oldRound = taskSupplier.getRound();
    if (taskSupplierMapper.unlockEffective(taskSupplierId, version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    int sheetsUnlocked = sheetMapper.unlockSubmittedByTaskSupplier(taskSupplierId);
    if (sheetsUnlocked == 0) {
      // 能到 EFFECTIVE 就说明全部评分单已提交（T3 要求数据完整）；0 行意味着状态机漏了入口
      throw new BizException(
          ErrorCode.INTERNAL_ERROR, "解锁时没有可解锁的评分单：taskSupplierId=" + taskSupplierId);
    }
    int dimensionsDeleted = dimensionResultMapper.deleteByTaskSupplier(taskSupplierId);
    if (dimensionsDeleted == 0) {
      throw new BizException(
          ErrorCode.INTERNAL_ERROR, "已生效评估单缺少维度快照：taskSupplierId=" + taskSupplierId);
    }
    int taskReopened = taskMapper.reopenIfCompleted(task.getId());

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("round", oldRound);
    detail.put("beforeTotalScore", taskSupplier.getTotalScore());
    detail.put("beforeTotalGrade", taskSupplier.getTotalGrade());
    detail.put("sheetsUnlocked", sheetsUnlocked);
    detail.put("dimensionsDeleted", dimensionsDeleted);
    detail.put("taskReopened", taskReopened == 1);
    if (normalizedComment != null) {
      detail.put("comment", AuditDetails.commentSummary(normalizedComment));
    }
    auditFacade.record(
        AuditAction.TASK_SUPPLIER_UNLOCK, AuditTargetType.TASK_SUPPLIER, taskSupplierId, detail);

    eventPublisher.publish(
        new TaskSupplierUnlockedEvent(
            taskSupplierId,
            taskSupplier.getSupplierId(),
            task.getYear(),
            task.getQuarter(),
            oldRound + 1,
            sheetMapper.findDistinctAssigneeIds(taskSupplierId)));

    log.info(
        "评估单解锁重评：taskSupplierId={} round={}->{} sheets={} dimensions={} taskReopened={}",
        taskSupplierId,
        oldRound,
        oldRound + 1,
        sheetsUnlocked,
        dimensionsDeleted,
        taskReopened == 1);
  }
}
