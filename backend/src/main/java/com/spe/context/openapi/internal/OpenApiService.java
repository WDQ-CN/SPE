package com.spe.context.openapi.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.context.evaluation.api.openapi.OpenApiQueryFacade;
import com.spe.context.evaluation.api.openapi.OpenApiScoreCommand;
import com.spe.context.evaluation.api.openapi.ScoreUpsertFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 开放 API 的编排层（M6）：只依赖 evaluation.api，Controller 只依赖本类与本地 DTO。
 *
 * <p>这样 R-E「Controller 只依赖自身上下文」与 R-A「跨上下文只走 api」同时成立。
 */
@Service
@RequiredArgsConstructor
public class OpenApiService {

  private final OpenApiQueryFacade queryFacade;
  private final ScoreUpsertFacade scoreUpsertFacade;

  public PageResult<OpenApiTaskResponse> activeTasks(PageQuery query) {
    PageResult<com.spe.context.evaluation.api.openapi.OpenApiTaskView> page =
        queryFacade.activeTasks(query);
    return PageResult.of(
        page.list().stream()
            .map(
                task ->
                    new OpenApiTaskResponse(
                        task.taskId(),
                        task.name(),
                        task.year(),
                        task.quarter(),
                        task.deadline(),
                        task.suppliers().stream()
                            .map(
                                supplier ->
                                    new OpenApiSupplierResponse(
                                        supplier.supplierId(),
                                        supplier.supplierCode(),
                                        supplier.supplierName(),
                                        supplier.status()))
                            .toList()))
            .toList(),
        page.total(),
        page.page(),
        page.size());
  }

  public OpenApiTaskIndicatorsResponse taskIndicators(long taskId) {
    var view = queryFacade.taskIndicators(taskId);
    return new OpenApiTaskIndicatorsResponse(
        view.taskId(),
        view.taskName(),
        view.year(),
        view.quarter(),
        view.dimensions().stream()
            .map(
                dimension ->
                    new OpenApiDimensionResponse(
                        dimension.dimensionId(),
                        dimension.name(),
                        dimension.weight(),
                        dimension.key(),
                        dimension.indicators().stream()
                            .map(
                                indicator ->
                                    new OpenApiIndicatorResponse(
                                        indicator.indicatorId(),
                                        indicator.code(),
                                        indicator.name(),
                                        indicator.weight(),
                                        indicator.type(),
                                        indicator.scoreMode(),
                                        indicator.unit(),
                                        indicator.targetValue(),
                                        indicator.standard()))
                            .toList()))
            .toList());
  }

  public OpenApiScoreResponse upsert(OpenApiScoreRequest request) {
    var result =
        scoreUpsertFacade.upsert(
            new OpenApiScoreCommand(
                request.taskId(),
                request.supplierCode(),
                request.indicatorCode(),
                request.rawValue()));
    return new OpenApiScoreResponse(
        result.taskId(),
        result.taskSupplierId(),
        result.supplierId(),
        result.supplierCode(),
        result.indicatorId(),
        result.indicatorCode(),
        result.rawValue(),
        result.scoreValue(),
        result.sheetId(),
        result.source());
  }
}
