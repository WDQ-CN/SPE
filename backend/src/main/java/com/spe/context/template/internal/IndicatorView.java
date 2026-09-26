package com.spe.context.template.internal;

import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import java.math.BigDecimal;

/**
 * 指标视图（HTTP 响应投影）：字段与 {@link Indicator} 一一对应，但**实体不出 Service 层**。
 *
 * <p>注意 {@code version} 是乐观锁版本，前端改/删时必须原样回传——守卫 UPDATE 用它作前置条件（12 §E12）。
 */
public record IndicatorView(
    long id,
    String name,
    String code,
    BigDecimal weight,
    IndicatorType type,
    ScoreMode scoreMode,
    String unit,
    BigDecimal targetValue,
    String standard,
    int sortNo,
    int version) {

  static IndicatorView of(Indicator indicator) {
    return new IndicatorView(
        indicator.getId(),
        indicator.getName(),
        indicator.getCode(),
        indicator.getWeight(),
        indicator.getType(),
        indicator.getScoreMode(),
        indicator.getUnit(),
        indicator.getTargetValue(),
        indicator.getStandard(),
        indicator.getSortNo() == null ? 0 : indicator.getSortNo(),
        indicator.getVersion() == null ? 0 : indicator.getVersion());
  }
}
