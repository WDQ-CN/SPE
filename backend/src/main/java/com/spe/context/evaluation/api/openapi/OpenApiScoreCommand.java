package com.spe.context.evaluation.api.openapi;

import java.math.BigDecimal;

/** 开放 API 上报一条原始值（M6 I1）：任务 + 供应商编码 + 指标编码 + 原始值。 */
public record OpenApiScoreCommand(
    long taskId, String supplierCode, String indicatorCode, BigDecimal rawValue) {}
