package com.spe.context.evaluation.internal.scoring.calculator;

import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import java.math.BigDecimal;

/**
 * 单指标的换算结果（13 §E5 的输出形态）。
 *
 * <p><b>缺值不是 0 分</b>（13 §E6）：未填的指标 {@code rawValue} 与 {@code scoreValue} 都是 null，
 * 调用方必须显式处理，不许用 {@code orElse(ZERO)} 之类把它变成 0。
 *
 * @param indicatorId 指标 id（= 评分记录的 {@code indicator_id}）
 * @param code 指标编码（Excel 导入与错误信息按它定位，01 T3）
 * @param name 指标名称
 * @param weight 维度内权重（0~100，不是模板内权重；12 §R-1）
 * @param type 定量 / 定性
 * @param scoreMode 计分方式；定性指标为 null（12 §E2）
 * @param rawValue 原始录入值；null = 未填
 * @param scoreValue 换算后的百分制分（2 位，HALF_UP）；未填时为 null
 * @param contribution 对维度分的贡献 {@code scoreValue × weight ÷ 100}（2 位，展示用）；未填时为 null
 */
public record IndicatorScore(
    long indicatorId,
    String code,
    String name,
    BigDecimal weight,
    IndicatorType type,
    ScoreMode scoreMode,
    BigDecimal rawValue,
    BigDecimal scoreValue,
    BigDecimal contribution) {

  /** 该指标是否已填（E6：判据只有这一处，调用方不要各自写 {@code rawValue != null}）。 */
  public boolean filled() {
    return rawValue != null;
  }
}
