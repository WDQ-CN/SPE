package com.spe.context.evaluation.api.openapi;

import java.math.BigDecimal;

/** 开放 API 上报结果（M6）：回显实际落库的 ID/分值，便于外部系统核对。 */
public record OpenApiScoreResult(
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
