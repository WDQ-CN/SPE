package com.spe.context.openapi.internal;

import java.math.BigDecimal;

/** 开放 API 指标（openapi 本地响应 DTO）。 */
public record OpenApiIndicatorResponse(
    long indicatorId,
    String code,
    String name,
    BigDecimal weight,
    String type,
    String scoreMode,
    String unit,
    BigDecimal targetValue,
    String standard) {}
