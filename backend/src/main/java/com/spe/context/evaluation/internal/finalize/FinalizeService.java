package com.spe.context.evaluation.internal.finalize;

import com.spe.common.event.EventPublisher;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.events.TaskSupplierFinalizedEvent;
import com.spe.context.evaluation.internal.approval.ApprovalRecordMapper;
import com.spe.context.evaluation.internal.approval.TaskSupplierDimensionResult;
import com.spe.context.evaluation.internal.approval.TaskSupplierDimensionResultMapper;
import com.spe.context.evaluation.internal.scoring.ScoreMapper;
import com.spe.context.evaluation.internal.scoring.ScoreValueAssembler;
import com.spe.context.evaluation.internal.scoring.calculator.ScoreCalculator;
import com.spe.context.evaluation.internal.scoring.calculator.ScoreSheetResult;
import com.spe.context.evaluation.internal.scoring.calculator.TaskSupplierScore;
import com.spe.context.evaluation.internal.sheet.SheetMapper;
import com.spe.context.evaluation.internal.task.EvalTask;
import com.spe.context.evaluation.internal.task.EvalTaskMapper;
import com.spe.context.evaluation.internal.task.TaskSupplier;
import com.spe.context.evaluation.internal.task.TaskSupplierMapper;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.api.SettingsFacade;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定稿（05 §5.5 T3 / 15 §E6）：末级通过后在同一事务里完成「守卫迁移 + 计算 + 快照 + 事件 + 完单」。
 *
 * <p>三处刻意：
 * <ol>
 *   <li>**先锁行、再读评分**：第一条语句是 `lockById`（当前读），read view 在拿到 X 锁之后才建立
 *       ⇒ 看到的是"已经定格的评分"（CLAUDE 默认约定第 6 条；评分单此时都是 SUBMITTED，没有并发写）。
 *   <li>**计算复用同一个纯函数**：`ScoreCalculator` 的第三处调用（打分预览 / 审批试算 / 生效定稿，ADR-4）。
 *   <li>**不完整就炸**：快照每维必须有分（15 §E13）；宁可整笔回滚，也不产出一份空报告。
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinalizeService {

  private final TaskSupplierMapper taskSupplierMapper;
  private final EvalTaskMapper taskMapper;
  private final SheetMapper sheetMapper;
  private final ScoreMapper scoreMapper;
  private final TaskSupplierDimensionResultMapper dimensionResultMapper;
  private final ApprovalRecordMapper approvalRecordMapper;
  private final TemplateSnapshotFacade templateSnapshotFacade;
  private final SettingsFacade settingsFacade;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  /**
   * 定稿一个评估单。
   *
   * @param taskSupplier 调用方已用 `lockById` 锁到的评估单行（T5 的末级通过路径）
   * @param lastNodeSeq 末级节点序号（守卫条件之一）
   * @param version 请求带来的评估单版本（末级通过不先改版本，故与库里一致）
   */
  @Transactional
  public void finalize(TaskSupplier taskSupplier, int lastNodeSeq, int version) {
    EvalTask task =
        taskMapper
            .findById(taskSupplier.getTaskId())
            .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(task.getTemplateVersionId());

    TaskSupplierScore result =
        ScoreCalculator.calculateTaskSupplier(
            snapshot,
            ScoreValueAssembler.byDimension(
                sheetMapper.findByTaskSupplierId(taskSupplier.getId()),
                scoreMapper.findByTaskSupplierId(taskSupplier.getId())),
            settingsFacade.gradeThresholds());
    if (!result.complete()) {
      List<String> missing =
          result.sheets().stream()
              .filter(sheet -> !sheet.complete())
              .map(ScoreSheetResult::dimensionName)
              .toList();
      throw new BizException(
          ErrorCode.INTERNAL_ERROR, "定稿失败：评估数据不完整，缺少维度：" + String.join("、", missing));
    }
    if (result.totalScore() == null || result.totalGrade() == null) {
      throw new BizException(ErrorCode.INTERNAL_ERROR, "定稿失败：总分或总等级为空");
    }

    // 守卫迁移 + 总分/等级（15 §E6 第 1、3 步）
    int moved =
        taskSupplierMapper.markEffective(
            taskSupplier.getId(), lastNodeSeq, version, result.totalScore(), result.totalGrade());
    if (moved == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    List<TaskSupplierDimensionResult> rows =
        result.sheets().stream()
            .map(
                sheet -> {
                  TaskSupplierDimensionResult row = new TaskSupplierDimensionResult();
                  row.setTaskSupplierId(taskSupplier.getId());
                  row.setDimensionId(sheet.dimensionId());
                  row.setDimensionName(sheet.dimensionName());
                  row.setWeight(sheet.weight());
                  row.setScore(sheet.score());
                  row.setGrade(sheet.grade());
                  return row;
                })
            .toList();
    int written = dimensionResultMapper.insertBatch(rows);
    if (written != snapshot.dimensions().size()) {
      throw new BizException(
          ErrorCode.INTERNAL_ERROR,
          "定稿失败：维度快照应写 " + snapshot.dimensions().size() + " 行，实际 " + written + " 行");
    }

    // 当前轮的末级 APPROVE 已由 ApprovalService 先写入；只数更早轮次，才能区分
    // 「驳回后第一次生效」与「解锁重评再定稿」——前者该触发趋势，后者不该（01 W2）。
    boolean firstEffective =
        approvalRecordMapper.countEarlierLastNodeApprovals(
                taskSupplier.getId(), lastNodeSeq, taskSupplier.getRound())
            == 0;
    eventPublisher.publish(
        new TaskSupplierFinalizedEvent(
            taskSupplier.getId(),
            taskSupplier.getSupplierId(),
            task.getYear(),
            task.getQuarter(),
            result.totalScore(),
            result.totalGrade(),
            result.sheets().stream()
                .map(
                    sheet ->
                        new TaskSupplierFinalizedEvent.DimensionResult(
                            sheet.dimensionId(),
                            sheet.dimensionName(),
                            sheet.weight(),
                            sheet.score(),
                            sheet.grade()))
                .toList(),
            firstEffective));

    int completed = taskMapper.completeIfAllEffective(task.getId());
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("totalScore", result.totalScore());
    detail.put("totalGrade", result.totalGrade());
    detail.put("dimensions", rows.size());
    // 01 J3 的完单结果并进这条留痕（15 §E10 的裁决：不新增动作码，0 行也照实记）
    detail.put("taskCompleted", completed == 1);
    auditFacade.record(
        AuditAction.TASK_SUPPLIER_FINALIZE, AuditTargetType.TASK, taskSupplier.getId(), detail);
    log.info(
        "评估单定稿：taskSupplierId={} totalScore={} totalGrade={} dimensions={} taskCompleted={}",
        taskSupplier.getId(),
        result.totalScore(),
        result.totalGrade(),
        rows.size(),
        completed == 1);
  }
}
