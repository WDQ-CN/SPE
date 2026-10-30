package com.spe.context.evaluation.api.openapi;

import java.math.BigDecimal;

/** 开放 API 的指标快照（M6）：字段与模板发布快照一致，type/scoreMode 用枚举名。 */
public record OpenApiIndicatorView(
    long indicatorId,
    String code,
    String name,
    BigDecimal weight,
    String type,
    String scoreMode,
    String unit,
    BigDecimal targetValue,
    String standard) {}
