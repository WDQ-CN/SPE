package com.spe.context.evaluation.internal.openapi;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.EvalTaskStatus;
import com.spe.context.evaluation.api.openapi.OpenApiScoreCommand;
import com.spe.context.evaluation.api.openapi.OpenApiScoreResult;
import com.spe.context.evaluation.api.openapi.ScoreUpsertFacade;
import com.spe.context.evaluation.internal.scoring.ScoreWriter;
import com.spe.context.evaluation.internal.sheet.Sheet;
import com.spe.context.evaluation.internal.sheet.SheetMapper;
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
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开放 API 上报（M6 I1/I2）：解析任务 + supplierCode + indicatorCode → sheet，再委托
 * {@link ScoreWriter#saveForApi} 完成换算/幂等 upsert。写路径只有这一条，不开旁路 Mapper。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreUpsertService implements ScoreUpsertFacade {

  private final EvalTaskMapper taskMapper;
  private final TaskSupplierMapper taskSupplierMapper;
  private final SupplierQueryFacade supplierQueryFacade;
  private final TemplateSnapshotFacade templateSnapshotFacade;
  private final SheetMapper sheetMapper;
  private final ScoreWriter scoreWriter;

  @Override
  @Transactional
  public OpenApiScoreResult upsert(OpenApiScoreCommand command) {
    EvalTask task =
        taskMapper
            .findById(command.taskId())
            .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    if (task.getStatus() != EvalTaskStatus.IN_PROGRESS) {
      throw new BizException(
          ErrorCode.TASK_STATUS_INVALID,
          "任务不在进行中，不能上报评分：" + task.getStatus());
    }

    String supplierCode = command.supplierCode() == null ? null : command.supplierCode().trim();
    SupplierBrief supplier =
        supplierQueryFacade
            .findByCode(supplierCode)
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_NOT_IN_TASK));
    TaskSupplier taskSupplier =
        taskSupplierMapper.findByTaskId(task.getId()).stream()
            .filter(row -> row.getSupplierId().equals(supplier.id()))
            .findFirst()
            .orElseThrow(
                () ->
                    new BizException(
                        ErrorCode.SUPPLIER_NOT_IN_TASK,
                        "供应商 " + supplierCode + " 不在该任务中"));

    String indicatorCode =
        command.indicatorCode() == null ? null : command.indicatorCode().trim();
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(task.getTemplateVersionId());
    DimensionSnapshot dimension = null;
    IndicatorSnapshot indicator = null;
    for (DimensionSnapshot candidate : snapshot.dimensions()) {
      for (IndicatorSnapshot item : candidate.indicators()) {
        if (item.code().equals(indicatorCode)) {
          dimension = candidate;
          indicator = item;
          break;
        }
      }
      if (indicator != null) {
        break;
      }
    }
    if (indicator == null) {
      throw new BizException(
          ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND,
          "指标编码不属于该任务：" + indicatorCode);
    }

    DimensionSnapshot resolvedDimension = dimension;
    Sheet sheet =
        sheetMapper.findByTaskSupplierId(taskSupplier.getId()).stream()
            .filter(row -> row.getDimensionId().equals(resolvedDimension.id()))
            .findFirst()
            .orElseThrow(
                () ->
                    new BizException(
                        ErrorCode.SHEET_NOT_FOUND,
                        "任务缺少维度评分单：taskSupplierId="
                            + taskSupplier.getId()
                            + "，dimensionId="
                            + resolvedDimension.id()));

    BigDecimal scoreValue =
        scoreWriter.saveForApi(sheet.getId(), indicator.id(), command.rawValue());
    log.info(
        "开放 API 上报：taskId={} supplierCode={} indicatorCode={} sheetId={}",
        task.getId(),
        supplier.code(),
        indicator.code(),
        sheet.getId());
    return new OpenApiScoreResult(
        task.getId(),
        taskSupplier.getId(),
        supplier.id(),
        supplier.code(),
        indicator.id(),
        indicator.code(),
        command.rawValue(),
        scoreValue,
        sheet.getId(),
        "API");
  }
}
