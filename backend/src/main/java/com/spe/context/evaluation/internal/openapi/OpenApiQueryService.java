package com.spe.context.evaluation.internal.openapi;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.EvalTaskStatus;
import com.spe.context.evaluation.api.openapi.OpenApiDimensionView;
import com.spe.context.evaluation.api.openapi.OpenApiIndicatorView;
import com.spe.context.evaluation.api.openapi.OpenApiQueryFacade;
import com.spe.context.evaluation.api.openapi.OpenApiSupplierView;
import com.spe.context.evaluation.api.openapi.OpenApiTaskIndicatorsView;
import com.spe.context.evaluation.api.openapi.OpenApiTaskView;
import com.spe.context.evaluation.internal.task.EvalTask;
import com.spe.context.evaluation.internal.task.EvalTaskMapper;
import com.spe.context.evaluation.internal.task.TaskSupplier;
import com.spe.context.evaluation.internal.task.TaskSupplierMapper;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开放 API 的只读实现（M6 W2）：进行中任务分页 + 任务指标快照。
 *
 * <p>读路径不新增 openapi QueryMapper：openapi 只经 evaluation.api 门面读（05 §5.1 / R-B）。
 * 供应商名称/编码批量补齐，不逐任务 N+1。
 */
@Service
@RequiredArgsConstructor
public class OpenApiQueryService implements OpenApiQueryFacade {

  private final EvalTaskMapper taskMapper;
  private final TaskSupplierMapper taskSupplierMapper;
  private final SupplierQueryFacade supplierQueryFacade;
  private final TemplateSnapshotFacade templateSnapshotFacade;

  @Override
  @Transactional(readOnly = true)
  public PageResult<OpenApiTaskView> activeTasks(PageQuery query) {
    List<EvalTask> tasks =
        taskMapper.findPage(
            EvalTaskStatus.IN_PROGRESS.name(), null, query.offset(), query.size());
    long total = taskMapper.countByFilter(EvalTaskStatus.IN_PROGRESS.name(), null);
    if (tasks.isEmpty()) {
      return PageResult.of(List.of(), total, query.page(), query.size());
    }
    List<Long> taskIds = tasks.stream().map(EvalTask::getId).toList();
    Map<Long, List<TaskSupplier>> taskSuppliers =
        taskSupplierMapper.findByTaskIds(taskIds).stream()
            .collect(Collectors.groupingBy(TaskSupplier::getTaskId));
    Set<Long> supplierIds =
        taskSuppliers.values().stream()
            .flatMap(List::stream)
            .map(TaskSupplier::getSupplierId)
            .collect(Collectors.toSet());
    Map<Long, SupplierBrief> suppliers =
        supplierIds.isEmpty()
            ? Map.of()
            : supplierQueryFacade.findByIds(supplierIds).stream()
                .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));

    List<OpenApiTaskView> rows =
        tasks.stream()
            .map(
                task ->
                    new OpenApiTaskView(
                        task.getId(),
                        task.getName(),
                        task.getYear(),
                        task.getQuarter(),
                        task.getDeadline(),
                        taskSuppliers.getOrDefault(task.getId(), List.of()).stream()
                            .map(ts -> supplierView(ts, suppliers.get(ts.getSupplierId())))
                            .toList()))
            .toList();
    return PageResult.of(rows, total, query.page(), query.size());
  }

  @Override
  @Transactional(readOnly = true)
  public OpenApiTaskIndicatorsView taskIndicators(long taskId) {
    EvalTask task =
        taskMapper.findById(taskId).orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    if (task.getStatus() != EvalTaskStatus.IN_PROGRESS) {
      throw new BizException(
          ErrorCode.TASK_STATUS_INVALID,
          "任务不在进行中，不能查询开放 API 指标：" + task.getStatus());
    }
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(task.getTemplateVersionId());
    List<OpenApiDimensionView> dimensions =
        snapshot.dimensions().stream()
            .map(
                dimension ->
                    new OpenApiDimensionView(
                        dimension.id(),
                        dimension.name(),
                        dimension.weight(),
                        dimension.isKey(),
                        dimension.indicators().stream()
                            .map(OpenApiQueryService::indicatorView)
                            .toList()))
            .toList();
    return new OpenApiTaskIndicatorsView(
        task.getId(), task.getName(), task.getYear(), task.getQuarter(), dimensions);
  }

  private static OpenApiSupplierView supplierView(TaskSupplier ts, SupplierBrief supplier) {
    return new OpenApiSupplierView(
        ts.getSupplierId(),
        supplier == null ? String.valueOf(ts.getSupplierId()) : supplier.code(),
        supplier == null ? null : supplier.name(),
        ts.getStatus().name());
  }

  private static OpenApiIndicatorView indicatorView(IndicatorSnapshot indicator) {
    return new OpenApiIndicatorView(
        indicator.id(),
        indicator.code(),
        indicator.name(),
        indicator.weight(),
        indicator.type().name(),
        indicator.scoreMode() == null ? null : indicator.scoreMode().name(),
        indicator.unit(),
        indicator.targetValue(),
        indicator.standard());
  }
}
