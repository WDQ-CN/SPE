package com.spe.context.evaluation.internal.approval;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.TaskSupplierStatus;
import com.spe.context.evaluation.api.events.ApprovalNodePassedEvent;
import com.spe.context.evaluation.api.events.TaskSupplierRejectedEvent;
import com.spe.context.evaluation.internal.finalize.FinalizeService;
import com.spe.context.evaluation.internal.sheet.SheetMapper;
import com.spe.context.evaluation.internal.task.ApprovalNodeMapper;
import com.spe.context.evaluation.internal.task.TaskSupplier;
import com.spe.context.evaluation.internal.task.TaskSupplierMapper;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审批流转（05 §5.5 T5 / 15 §E5）：我的待办、通过、驳回。
 *
 * <p><b>授权分两层</b>（15 §E8、01 §3）：Controller 的角色注解只决定"能不能进这个入口"；真正的授权
 * 在这里——**当前节点是不是你**（`approval_node.seq = task_supplier.current_node`），不是就 4003，
 * 与"这张评分单是否指派给我"同形（04-096 的教训：第二层不能只在一条写路径上做）。
 *
 * <p><b>并发</b>：每个写方法的第一条语句都是 `lockById`（X 锁），它同时给出"当前节点/版本/轮次"的当前值；
 * 随后的守卫 UPDATE 再钉一次状态条件，0 行一律 3001（06 §2.4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

  private static final int COMMENT_MAX_LENGTH = 500;

  private final TaskSupplierMapper taskSupplierMapper;
  private final ApprovalNodeMapper approvalNodeMapper;
  private final ApprovalRecordMapper approvalRecordMapper;
  private final SheetMapper sheetMapper;
  private final SupplierQueryFacade supplierQueryFacade;
  private final FinalizeService finalizeService;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  /** 我的审批待办（15 §E8）：当前节点是我、状态在审批中。供应商名称批量补齐，不逐行回查。 */
  @Transactional(readOnly = true)
  public PageResult<ApprovalTodoView> listMyApprovals(long operatorId, PageQuery query) {
    long total = taskSupplierMapper.countMyApprovals(operatorId);
    if (total == 0) {
      return new PageResult<>(List.of(), 0, query.page(), query.size());
    }
    List<ApprovalTodoRow> rows =
        taskSupplierMapper.findMyApprovals(operatorId, query.offset(), query.size());
    Map<Long, SupplierBrief> suppliers = new LinkedHashMap<>();
    supplierQueryFacade
        .findByIds(rows.stream().map(ApprovalTodoRow::getSupplierId).distinct().toList())
        .forEach(brief -> suppliers.put(brief.id(), brief));
    List<ApprovalTodoView> views = new ArrayList<>(rows.size());
    for (ApprovalTodoRow row : rows) {
      SupplierBrief supplier = suppliers.get(row.getSupplierId());
      views.add(
          new ApprovalTodoView(
              row.getTaskSupplierId(),
              row.getTaskId(),
              row.getTaskName(),
              row.getYear(),
              row.getQuarter(),
              row.getDeadline(),
              row.getSupplierId(),
              supplier == null ? null : supplier.code(),
              supplier == null ? null : supplier.name(),
              TaskSupplierStatus.valueOf(row.getStatus()),
              row.getCurrentNode(),
              row.getRound(),
              row.getVersion()));
    }
    return new PageResult<>(views, total, query.page(), query.size());
  }

  /**
   * 通过（15 §E5）：非末级 → `APPROVING` + 节点 +1；末级 → 写审批记录后**直接进 T3 定稿**。
   *
   * <p>末级这一步**不先把状态改成 EFFECTIVE**：T3 的守卫迁移才是唯一入口，否则 T3 的 0 行冲突检测
   * 永远看不到（15 §9.1 的自审）。
   */
  @Transactional
  public void approve(long taskSupplierId, long operatorId, int version) {
    TaskSupplier taskSupplier = requireLocked(taskSupplierId);
    requireApprovable(taskSupplier);
    int currentNode = taskSupplier.getCurrentNode();
    Long approverId = approvalNodeMapper.findApproverId(taskSupplier.getTaskId(), currentNode);
    if (approverId == null) {
      throw new BizException(
          ErrorCode.INTERNAL_ERROR,
          "审批链缺少当前节点：taskId=" + taskSupplier.getTaskId() + " seq=" + currentNode);
    }
    if (approverId != operatorId) {
      throw new BizException(ErrorCode.APPROVAL_NOT_YOUR_NODE);
    }
    if (taskSupplier.getVersion() != version) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    Integer maxSeq = approvalNodeMapper.findMaxSeq(taskSupplier.getTaskId());
    if (maxSeq == null) {
      throw new BizException(
          ErrorCode.INTERNAL_ERROR, "任务没有审批链：taskId=" + taskSupplier.getTaskId());
    }
    boolean last = currentNode >= maxSeq;
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("round", taskSupplier.getRound());
    detail.put("seq", currentNode);
    detail.put("lastLevel", last);

    if (last) {
      // 记录与审计先落（同一事务；定稿失败会一起回滚），再由 T3 完成 EFFECTIVE + 分数 + 快照 + 事件。
      // 审计放在 finalize 之前，让 audit_log 的先后与"通过 → 定稿"的业务顺序一致。
      appendRecord(taskSupplier, currentNode, operatorId, "APPROVE", null);
      auditFacade.record(AuditAction.APPROVAL_APPROVE, AuditTargetType.TASK_SUPPLIER, taskSupplierId, detail);
      finalizeService.finalize(taskSupplier, maxSeq, version);
    } else {
      Long nextApproverId = approvalNodeMapper.findApproverId(taskSupplier.getTaskId(), currentNode + 1);
      if (nextApproverId == null) {
        throw new BizException(
            ErrorCode.INTERNAL_ERROR,
            "审批链缺少下一节点：taskId=" + taskSupplier.getTaskId() + " seq=" + (currentNode + 1));
      }
      if (taskSupplierMapper.markApproving(taskSupplierId, currentNode, version) == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
      appendRecord(taskSupplier, currentNode, operatorId, "APPROVE", null);
      auditFacade.record(AuditAction.APPROVAL_APPROVE, AuditTargetType.TASK_SUPPLIER, taskSupplierId, detail);
      eventPublisher.publish(new ApprovalNodePassedEvent(taskSupplierId, nextApproverId));
    }

    log.info(
        "审批通过：taskSupplierId={} approverId={} round={} seq={} lastLevel={}",
        taskSupplierId,
        operatorId,
        taskSupplier.getRound(),
        currentNode,
        last);
  }

  /**
   * 驳回（15 §E5）：状态回 `SCORING`、节点清零、**轮次 +1** 写在同一条守卫 UPDATE；随后解锁全部
   * 已提交评分单并通知涉及的评估人。
   */
  @Transactional
  public void reject(long taskSupplierId, long operatorId, String comment, int version) {
    TaskSupplier taskSupplier = requireLocked(taskSupplierId);
    requireApprovable(taskSupplier);
    int currentNode = taskSupplier.getCurrentNode();
    Long approverId = approvalNodeMapper.findApproverId(taskSupplier.getTaskId(), currentNode);
    if (approverId == null) {
      throw new BizException(
          ErrorCode.INTERNAL_ERROR,
          "审批链缺少当前节点：taskId=" + taskSupplier.getTaskId() + " seq=" + currentNode);
    }
    if (approverId != operatorId) {
      throw new BizException(ErrorCode.APPROVAL_NOT_YOUR_NODE);
    }
    if (taskSupplier.getVersion() != version) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    String normalizedComment = comment == null ? "" : comment.trim();
    if (normalizedComment.isEmpty()) {
      throw new BizException(ErrorCode.APPROVAL_COMMENT_REQUIRED);
    }
    if (normalizedComment.length() > COMMENT_MAX_LENGTH) {
      throw new BizException(ErrorCode.BAD_REQUEST, "驳回意见长度不得超过 500 字");
    }

    int oldRound = taskSupplier.getRound();
    if (taskSupplierMapper.markRejected(taskSupplierId, currentNode, version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    int unlocked = sheetMapper.unlockSubmittedByTaskSupplier(taskSupplierId);
    if (unlocked == 0) {
      // 能进审批态就说明全部评分单已提交（T2）；0 行意味着状态机漏了入口，必须响亮失败
      throw new BizException(
          ErrorCode.INTERNAL_ERROR, "驳回时没有可解锁的评分单：taskSupplierId=" + taskSupplierId);
    }
    appendRecord(taskSupplier, currentNode, operatorId, "REJECT", normalizedComment);
    eventPublisher.publish(
        new TaskSupplierRejectedEvent(
            taskSupplierId, normalizedComment, sheetMapper.findDistinctAssigneeIds(taskSupplierId)));

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("round", oldRound);
    detail.put("seq", currentNode);
    detail.put("unlockedSheets", unlocked);
    detail.put("comment", AuditDetails.commentSummary(normalizedComment));
    auditFacade.record(AuditAction.APPROVAL_REJECT, AuditTargetType.TASK_SUPPLIER, taskSupplierId, detail);
    log.info(
        "审批驳回：taskSupplierId={} approverId={} round={} seq={} unlockedSheets={}",
        taskSupplierId,
        operatorId,
        oldRound,
        currentNode,
        unlocked);
  }

  // ---------- 内部 ----------

  /**
   * 锁住评估单并给出当前值（当前读）。`FOR UPDATE` 同时充当同一评估单审批动作的串行化点：
   * 通过与驳回、两个审批人并发时，后到者会看到已推进的 `current_node` 而返回 4003/3001。
   */
  private TaskSupplier requireLocked(long taskSupplierId) {
    return taskSupplierMapper
        .lockById(taskSupplierId)
        .orElseThrow(() -> new BizException(ErrorCode.TASK_SUPPLIER_NOT_FOUND));
  }

  private static void requireApprovable(TaskSupplier taskSupplier) {
    TaskSupplierStatus status = taskSupplier.getStatus();
    if (status != TaskSupplierStatus.PENDING_APPROVAL
        && status != TaskSupplierStatus.APPROVING) {
      throw new BizException(
          ErrorCode.TASK_STATUS_INVALID, "评估单当前状态不允许审批：" + status);
    }
  }

  /** 审批记录的身份是 `(round, seq)`：round 取**动作发生时**的轮次（驳回 +1 之前的值）。 */
  private void appendRecord(
      TaskSupplier taskSupplier, int seq, long approverId, String action, String comment) {
    ApprovalRecord record = new ApprovalRecord();
    record.setTaskSupplierId(taskSupplier.getId());
    record.setRound(taskSupplier.getRound());
    record.setSeq(seq);
    record.setApproverId(approverId);
    record.setAction(action);
    record.setComment(comment);
    approvalRecordMapper.insertBatch(List.of(record));
  }
}
