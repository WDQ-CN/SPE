package com.spe.context.evaluation.api.openapi;

import java.math.BigDecimal;
import java.util.List;

/** 开放 API 的维度快照（M6）：含维度权重/关键标记与指标清单。 */
public record OpenApiDimensionView(
    long dimensionId,
    String name,
    BigDecimal weight,
    boolean key,
    List<OpenApiIndicatorView> indicators) {

  public OpenApiDimensionView {
    indicators = List.copyOf(indicators);
  }
}
