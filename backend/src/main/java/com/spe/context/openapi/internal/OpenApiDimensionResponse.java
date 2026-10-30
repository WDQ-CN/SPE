package com.spe.context.openapi.internal;

import java.math.BigDecimal;
import java.util.List;

/** 开放 API 维度（openapi 本地响应 DTO）。 */
public record OpenApiDimensionResponse(
    long dimensionId,
    String name,
    BigDecimal weight,
    boolean key,
    List<OpenApiIndicatorResponse> indicators) {

  public OpenApiDimensionResponse {
    indicators = List.copyOf(indicators);
  }
}
