package com.spe.context.openapi.internal;

import java.math.BigDecimal;

/** 开放 API 上报结果（openapi 本地响应 DTO）。 */
public record OpenApiScoreResponse(
    long taskId,
    long taskSupplierId,
    long supplierId,
    String supplierCode,
    long indicatorId,
    String indicatorCode,
    BigDecimal rawValue,
    BigDecimal scoreValue,
    long sheetId,
    String source) {}
