package com.spe.context.evaluation.api.openapi;

import java.util.List;

/** 开放 API 的任务指标清单（M6 I1）：来自任务绑定的已发布版本快照。 */
public record OpenApiTaskIndicatorsView(
    long taskId,
    String taskName,
    int year,
    int quarter,
    List<OpenApiDimensionView> dimensions) {

  public OpenApiTaskIndicatorsView {
    dimensions = List.copyOf(dimensions);
  }
}
