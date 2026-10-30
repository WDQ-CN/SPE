package com.spe.context.openapi.internal;

import java.time.LocalDate;
import java.util.List;

/** 开放 API 进行中任务（openapi 本地响应 DTO）。 */
public record OpenApiTaskResponse(
    long taskId,
    String name,
    int year,
    int quarter,
    LocalDate deadline,
    List<OpenApiSupplierResponse> suppliers) {

  public OpenApiTaskResponse {
    suppliers = List.copyOf(suppliers);
  }
}
