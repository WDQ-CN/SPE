package com.spe.context.openapi.internal;

import java.util.List;

/** 开放 API 任务指标清单（openapi 本地响应 DTO）。 */
public record OpenApiTaskIndicatorsResponse(
    long taskId,
    String taskName,
    int year,
    int quarter,
    List<OpenApiDimensionResponse> dimensions) {

  public OpenApiTaskIndicatorsResponse {
    dimensions = List.copyOf(dimensions);
  }
}
