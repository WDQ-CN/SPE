package com.spe.context.evaluation.internal.result;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.sql.SqlKeywords;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.EvalTaskStatus;
import com.spe.context.evaluation.api.TaskSupplierStatus;
import com.spe.context.evaluation.internal.approval.ApprovalRecord;
import com.spe.context.evaluation.internal.approval.ApprovalRecordMapper;
import com.spe.context.evaluation.internal.approval.TaskSupplierDimensionResult;
import com.spe.context.evaluation.internal.approval.TaskSupplierDimensionResultMapper;
import com.spe.context.evaluation.internal.scoring.Score;
import com.spe.context.evaluation.internal.scoring.ScoreMapper;
import com.spe.context.evaluation.internal.scoring.ScoreValueAssembler;
import com.spe.context.evaluation.internal.scoring.calculator.IndicatorScore;
import com.spe.context.evaluation.internal.scoring.calculator.ScoreCalculator;
import com.spe.context.evaluation.internal.scoring.calculator.ScoreSheetResult;
import com.spe.context.evaluation.internal.scoring.calculator.TaskSupplierScore;
import com.spe.context.evaluation.internal.sheet.Sheet;
import com.spe.context.evaluation.internal.sheet.SheetMapper;
import com.spe.context.evaluation.internal.task.ApprovalNodeMapper;
import com.spe.context.evaluation.internal.task.EvalTask;
import com.spe.context.evaluation.internal.task.EvalTaskMapper;
import com.spe.context.evaluation.internal.task.TaskSupplier;
import com.spe.context.evaluation.internal.task.TaskSupplierMapper;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.SettingsFacade;
import com.spe.context.system.api.UserBrief;
import com.spe.context.system.api.UserQueryFacade;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结果列表与评估报告（01 R2/R3 / 02 §4 / 15 §E14/E15）。
 *
 * <p><b>两套口径</b>：已生效读定稿快照；未生效降级为 `ScoreCalculator` 试算并标 `TRIAL`
 * （01 R1「审批中各级看到的试算分仅供决策」）。两处都用同一份 {@link ScoreValueAssembler}，
 * 避免报告与定稿各算一套。
 *
 * <p><b>可见性</b>：结果列表由 Controller 的角色注解限制；报告是 `isAuthenticated`，第二层在这里——
 * 评估人只能看**本人参与过**的评估单（01 §7），其余角色按矩阵全量可见。
 */
@Service
@RequiredArgsConstructor
public class ResultReportService {

  private final EvalTaskMapper taskMapper;
  private final ResultTaskMapper resultTaskMapper;
  private final TaskSupplierMapper taskSupplierMapper;
  private final ApprovalNodeMapper approvalNodeMapper;
  private final TaskSupplierDimensionResultMapper dimensionResultMapper;
  private final ApprovalRecordMapper approvalRecordMapper;
  private final SheetMapper sheetMapper;
  private final ScoreMapper scoreMapper;
  private final TemplateSnapshotFacade templateSnapshotFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final UserQueryFacade userQueryFacade;
  private final SettingsFacade settingsFacade;

  /** 结果报表入口：只列**已有已生效评估单**的任务（VIEWER 也可读，走本上下文的最小投影）。 */
  @Transactional(readOnly = true)
  public PageResult<ResultTaskSummary> resultTasks(
      PageQuery query, EvalTaskStatus status, String keyword) {
    String filterKeyword =
        keyword == null || keyword.isBlank() ? null : SqlKeywords.escapeLike(keyword.trim());
    String filterStatus = status == null ? null : status.name();
    long total = resultTaskMapper.countTasksWithResults(filterStatus, filterKeyword);
    if (total == 0) {
      return new PageResult<>(List.of(), 0, query.page(), query.size());
    }
    List<ResultTaskSummary> rows =
        resultTaskMapper
            .findTasksWithResults(filterStatus, filterKeyword, query.offset(), query.size())
            .stream()
            .map(
                row ->
                    new ResultTaskSummary(
                        row.getTaskId(),
                        row.getTaskName(),
                        row.getYear(),
                        row.getQuarter(),
                        row.getDeadline(),
                        EvalTaskStatus.valueOf(row.getStatus()),
                        row.getResultCount() == null ? 0 : row.getResultCount()))
            .toList();
    return new PageResult<>(rows, total, query.page(), query.size());
  }

  /** 任务级结果列表：只含 `EFFECTIVE` 的评估单（01 R2）。 */
  @Transactional(readOnly = true)
  public TaskResults results(long taskId) {
    EvalTask task =
        taskMapper.findById(taskId).orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    List<TaskSupplier> effective =
        taskSupplierMapper.findByTaskId(taskId).stream()
            .filter(taskSupplier -> taskSupplier.getStatus() == TaskSupplierStatus.EFFECTIVE)
            .toList();
    if (effective.isEmpty()) {
      return new TaskResults(taskId, task.getName(), task.getYear(), task.getQuarter(), List.of());
    }
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(task.getTemplateVersionId());
    Map<Long, List<TaskSupplierDimensionResult>> snapshotsByTaskSupplier =
        dimensionResultMapper
            .findByTaskSupplierIds(effective.stream().map(TaskSupplier::getId).toList())
            .stream()
            .collect(Collectors.groupingBy(TaskSupplierDimensionResult::getTaskSupplierId));
    Map<Long, SupplierBrief> suppliers = supplierBriefs(effective);

    List<TaskResults.Row> rows = new ArrayList<>(effective.size());
    for (TaskSupplier taskSupplier : effective) {
      Map<Long, TaskSupplierDimensionResult> byDimension =
          snapshotsByTaskSupplier.getOrDefault(taskSupplier.getId(), List.of()).stream()
              .collect(
                  Collectors.toMap(TaskSupplierDimensionResult::getDimensionId, Function.identity()));
      List<TaskResults.DimensionScore> dimensions = new ArrayList<>(snapshot.dimensions().size());
      for (DimensionSnapshot dimension : snapshot.dimensions()) {
        TaskSupplierDimensionResult saved = byDimension.get(dimension.id());
        if (saved == null) {
          throw new BizException(
              ErrorCode.INTERNAL_ERROR,
              "已生效评估单缺少维度快照：taskSupplierId="
                  + taskSupplier.getId()
                  + " dimensionId="
                  + dimension.id());
        }
        dimensions.add(
            new TaskResults.DimensionScore(
                saved.getDimensionId(),
                saved.getDimensionName(),
                saved.getWeight(),
                saved.getScore(),
                saved.getGrade()));
      }
      SupplierBrief supplier = suppliers.get(taskSupplier.getSupplierId());
      rows.add(
          new TaskResults.Row(
              taskSupplier.getId(),
              taskSupplier.getSupplierId(),
              supplier.code(),
              supplier.name(),
              taskSupplier.getTotalScore(),
              taskSupplier.getTotalGrade(),
              dimensions));
    }
    return new TaskResults(taskId, task.getName(), task.getYear(), task.getQuarter(), rows);
  }

  /**
   * 评估报告。
   *
   * @param privileged ADMIN/MANAGER/VIEWER 为 true（Controller 按角色判定）；评估人必须参与过本评估单
   */
  @Transactional(readOnly = true)
  public TaskSupplierReport report(long taskSupplierId, long operatorId, boolean privileged) {
    TaskSupplier taskSupplier = requireReadable(taskSupplierId, operatorId, privileged);
    EvalTask task =
        taskMapper
            .findById(taskSupplier.getTaskId())
            .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(task.getTemplateVersionId());

    List<Sheet> sheets = sheetMapper.findByTaskSupplierId(taskSupplierId);
    List<Score> scores = scoreMapper.findByTaskSupplierId(taskSupplierId);
    Map<Long, Score> scoreByIndicator =
        scores.stream()
            .collect(Collectors.toMap(Score::getIndicatorId, Function.identity(), (left, right) -> right));
    TaskSupplierScore calculated =
        ScoreCalculator.calculateTaskSupplier(
            snapshot, ScoreValueAssembler.byDimension(sheets, scores), settingsFacade.gradeThresholds());

    boolean effective = taskSupplier.getStatus() == TaskSupplierStatus.EFFECTIVE;
    Map<Long, TaskSupplierDimensionResult> savedByDimension = savedSnapshots(taskSupplierId, effective);
    List<TaskSupplierReport.Dimension> dimensions =
        buildDimensions(snapshot, calculated, scoreByIndicator, savedByDimension, effective, taskSupplierId);

    SupplierBrief supplier =
        supplierQueryFacade
            .findById(taskSupplier.getSupplierId())
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_NOT_FOUND));

    // 「此刻这一节点是不是你」（03 §41.3 的 P2-27）：状态可审 + 当前节点审批人 == 登录人。
    // 链缺节点时 findApproverId 为 null，判 false；绝不因此炸掉报告读取（读路径不该被配置缺口拖死）。
    Long currentApproverId =
        approvalNodeMapper.findApproverId(task.getId(), taskSupplier.getCurrentNode());
    boolean actableNow = currentApproverId != null && currentApproverId == operatorId;

    return new TaskSupplierReport(
        taskSupplierId,
        task.getId(),
        task.getName(),
        task.getYear(),
        task.getQuarter(),
        task.getDeadline(),
        taskSupplier.getSupplierId(),
        supplier.code(),
        supplier.name(),
        taskSupplier.getStatus(),
        taskSupplier.getCurrentNode(),
        taskSupplier.getRound(),
        taskSupplier.getVersion(),
        effective ? "SNAPSHOT" : "TRIAL",
        effective ? taskSupplier.getTotalScore() : calculated.totalScore(),
        effective ? taskSupplier.getTotalGrade() : calculated.totalGrade(),
        effective || calculated.complete(),
        actableNow,
        dimensions,
        timeline(taskSupplierId));
  }

  private TaskSupplier requireReadable(long taskSupplierId, long operatorId, boolean privileged) {
    TaskSupplier taskSupplier =
        taskSupplierMapper
            .findById(taskSupplierId)
            .orElseThrow(() -> new BizException(ErrorCode.TASK_SUPPLIER_NOT_FOUND));
    if (!privileged
        && sheetMapper.countByTaskSupplierIdAndAssignee(taskSupplierId, operatorId) == 0) {
      throw new BizException(ErrorCode.FORBIDDEN);
    }
    return taskSupplier;
  }

  private Map<Long, TaskSupplierDimensionResult> savedSnapshots(long taskSupplierId, boolean effective) {
    if (!effective) {
      return Map.of();
    }
    return dimensionResultMapper.findByTaskSupplier(taskSupplierId).stream()
        .collect(Collectors.toMap(TaskSupplierDimensionResult::getDimensionId, Function.identity()));
  }

  private List<TaskSupplierReport.Dimension> buildDimensions(
      TemplateSnapshot snapshot,
      TaskSupplierScore calculated,
      Map<Long, Score> scoreByIndicator,
      Map<Long, TaskSupplierDimensionResult> savedByDimension,
      boolean effective,
      long taskSupplierId) {
    Map<Long, IndicatorSnapshot> indicatorSnapshots = new HashMap<>();
    for (DimensionSnapshot dimension : snapshot.dimensions()) {
      for (IndicatorSnapshot indicator : dimension.indicators()) {
        indicatorSnapshots.put(indicator.id(), indicator);
      }
    }
    List<TaskSupplierReport.Dimension> dimensions = new ArrayList<>(snapshot.dimensions().size());
    for (DimensionSnapshot dimension : snapshot.dimensions()) {
      ScoreSheetResult sheetResult = sheetResultOf(calculated, dimension.id());
      TaskSupplierDimensionResult saved = savedByDimension.get(dimension.id());
      if (effective && saved == null) {
        throw new BizException(
            ErrorCode.INTERNAL_ERROR,
            "已生效评估单缺少维度快照：taskSupplierId=" + taskSupplierId + " dimensionId=" + dimension.id());
      }
      BigDecimal score = effective ? saved.getScore() : sheetResult.score();
      String grade = effective ? saved.getGrade() : sheetResult.grade();
      dimensions.add(
          new TaskSupplierReport.Dimension(
              dimension.id(),
              dimension.name(),
              dimension.weight(),
              score,
              grade,
              effective || sheetResult.complete(),
              buildIndicators(sheetResult, indicatorSnapshots, scoreByIndicator)));
    }
    return dimensions;
  }

  private List<TaskSupplierReport.Indicator> buildIndicators(
      ScoreSheetResult sheetResult,
      Map<Long, IndicatorSnapshot> indicatorSnapshots,
      Map<Long, Score> scoreByIndicator) {
    List<TaskSupplierReport.Indicator> indicators = new ArrayList<>(sheetResult.indicators().size());
    for (IndicatorScore indicator : sheetResult.indicators()) {
      IndicatorSnapshot meta = indicatorSnapshots.get(indicator.indicatorId());
      if (meta == null) {
        throw new BizException(
            ErrorCode.INTERNAL_ERROR, "指标不在模板快照中：indicatorId=" + indicator.indicatorId());
      }
      Score scoreRow = scoreByIndicator.get(indicator.indicatorId());
      indicators.add(
          new TaskSupplierReport.Indicator(
              indicator.indicatorId(),
              indicator.code(),
              indicator.name(),
              indicator.weight(),
              indicator.type(),
              indicator.scoreMode(),
              indicator.rawValue(),
              indicator.scoreValue(),
              meta.unit(),
              scoreRow == null ? null : scoreRow.getSource()));
    }
    return indicators;
  }

  private List<TaskSupplierReport.TimelineEntry> timeline(long taskSupplierId) {
    List<ApprovalRecord> records = approvalRecordMapper.findByTaskSupplier(taskSupplierId);
    if (records.isEmpty()) {
      return List.of();
    }
    Map<Long, String> approverNames = new LinkedHashMap<>();
    userQueryFacade
        .findByIds(records.stream().map(ApprovalRecord::getApproverId).distinct().toList())
        .forEach(brief -> approverNames.put(brief.id(), brief.name()));
    return records.stream()
        .map(
            record ->
                new TaskSupplierReport.TimelineEntry(
                    record.getRound(),
                    record.getSeq(),
                    record.getApproverId(),
                    approverNames.get(record.getApproverId()),
                    record.getAction(),
                    record.getComment(),
                    record.getCreatedAt()))
        .toList();
  }

  private Map<Long, SupplierBrief> supplierBriefs(List<TaskSupplier> taskSuppliers) {
    Map<Long, SupplierBrief> suppliers = new HashMap<>();
    supplierQueryFacade
        .findByIds(taskSuppliers.stream().map(TaskSupplier::getSupplierId).distinct().toList())
        .forEach(brief -> suppliers.put(brief.id(), brief));
    for (TaskSupplier taskSupplier : taskSuppliers) {
      if (!suppliers.containsKey(taskSupplier.getSupplierId())) {
        throw new BizException(ErrorCode.SUPPLIER_NOT_FOUND);
      }
    }
    return suppliers;
  }

  private static ScoreSheetResult sheetResultOf(TaskSupplierScore score, long dimensionId) {
    return score.sheets().stream()
        .filter(sheet -> sheet.dimensionId() == dimensionId)
        .findFirst()
        .orElseThrow(
            () ->
                new BizException(
                    ErrorCode.INTERNAL_ERROR, "试算结果缺少维度：dimensionId=" + dimensionId));
  }
}
