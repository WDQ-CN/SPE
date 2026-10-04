package com.spe.context.evaluation.internal.task;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.internal.scoring.Score;
import com.spe.context.evaluation.internal.scoring.ScoreMapper;
import com.spe.context.evaluation.internal.scoring.calculator.TaskSupplierScore;
import com.spe.context.evaluation.internal.sheet.Sheet;
import com.spe.context.evaluation.internal.sheet.SheetMapper;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.GradeThresholds;
import com.spe.context.system.api.SettingsFacade;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 进度看板（01 J3 / 13 §E14）：每个供应商一行，含试算得分、当前审批节点、未指派/未提交计数。
 *
 * <p><b>SQL 条数与供应商数无关</b>（E14 的验收挂钩）：
 * <ol>
 *   <li>1 条：任务行；
 *   <li>N 条（常数）：模板快照（版本 + 维度 + 指标，由 template 上下文决定，与供应商无关）；
 *   <li>1 条：`task_supplier LEFT JOIN sheet` 的聚合（行 + 两个计数）；
 *   <li>1 条：该任务全部评分单（建 sheet → 维度映射）；
 *   <li>1 条：该任务全部 score（一次取回内存，按供应商分组试算——**试算是纯函数，不产生 IO**）；
 *   <li>1 条：供应商名称批量补齐。
 * </ol>
 * 集成用例用 `performance_schema.events_statements_summary_by_digest` 数语句数，断言"1 个供应商"与
 * "4 个供应商"两次调用的语句数**相等**（不是靠延迟——CLAUDE 审查维度第 3 条）。
 */
@Service
@RequiredArgsConstructor
public class TaskProgressService {

  private final EvalTaskMapper taskMapper;
  private final TaskSupplierMapper taskSupplierMapper;
  private final SheetMapper sheetMapper;
  private final ScoreMapper scoreMapper;
  private final TemplateSnapshotFacade templateSnapshotFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final SettingsFacade settingsFacade;

  @Transactional(readOnly = true)
  public TaskProgress progress(long taskId) {
    EvalTask task =
        taskMapper.findById(taskId).orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(task.getTemplateVersionId());
    List<TaskProgressRow> rows = taskSupplierMapper.findProgressRows(taskId);
    if (rows.isEmpty()) {
      return new TaskProgress(taskId, List.of());
    }
    Map<Long, SupplierBrief> suppliers = new LinkedHashMap<>();
    supplierQueryFacade
        .findByIds(rows.stream().map(TaskProgressRow::getSupplierId).toList())
        .forEach(brief -> suppliers.put(brief.id(), brief));

    // sheet → (评估单, 维度)：试算要按"评估单 × 维度"组装原始值
    Map<Long, Long> taskSupplierBySheet = new HashMap<>();
    Map<Long, Long> dimensionBySheet = new HashMap<>();
    for (Sheet sheet : sheetMapper.findByTaskId(taskId)) {
      taskSupplierBySheet.put(sheet.getId(), sheet.getTaskSupplierId());
      dimensionBySheet.put(sheet.getId(), sheet.getDimensionId());
    }
    Map<Long, Map<Long, Map<Long, BigDecimal>>> valuesByTaskSupplier = new HashMap<>();
    for (Score score : scoreMapper.findByTaskId(taskId)) {
      Long taskSupplierId = taskSupplierBySheet.get(score.getSheetId());
      Long dimensionId = dimensionBySheet.get(score.getSheetId());
      if (taskSupplierId == null || dimensionId == null || score.getRawValue() == null) {
        continue;
      }
      valuesByTaskSupplier
          .computeIfAbsent(taskSupplierId, key -> new HashMap<>())
          .computeIfAbsent(dimensionId, key -> new LinkedHashMap<>())
          .put(score.getIndicatorId(), score.getRawValue());
    }

    GradeThresholds thresholds = settingsFacade.gradeThresholds();
    List<TaskProgress.Row> result = new ArrayList<>(rows.size());
    for (TaskProgressRow row : rows) {
      TaskSupplierScore score =
          com.spe.context.evaluation.internal.scoring.calculator.ScoreCalculator
              .calculateTaskSupplier(
                  snapshot,
                  valuesByTaskSupplier.getOrDefault(row.getTaskSupplierId(), Map.of()),
                  thresholds);
      SupplierBrief supplier = suppliers.get(row.getSupplierId());
      result.add(
          new TaskProgress.Row(
              row.getTaskSupplierId(),
              row.getSupplierId(),
              supplier == null ? null : supplier.code(),
              supplier == null ? null : supplier.name(),
              row.getStatus(),
              row.getCurrentNode(),
              score.totalScore(),
              score.totalGrade(),
              score.complete(),
              row.getUnassignedSheets() == null ? 0 : row.getUnassignedSheets(),
              row.getUnsubmittedSheets() == null ? 0 : row.getUnsubmittedSheets()));
    }
    return new TaskProgress(taskId, result);
  }
}
