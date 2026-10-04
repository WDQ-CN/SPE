package com.spe.context.evaluation.internal.scoring.calculator;

import com.spe.context.template.api.IndicatorSnapshot;
import java.math.BigDecimal;

/**
 * 单个指标的换算策略（06 §2.5 / 05 ADR-4 / 13 §E5）：**纯函数**，只做「原始值 → 百分制分」。
 *
 * <p>策略表让 06 §2.5 的「新增计分方式 = 新策略 + 枚举登记」成立：加一种算法只需新增一个策略常量，
 * 既有策略一行不改。
 *
 * <p>返回的分数**不做舍入**——舍入只在最终输出处发生（13 §E5）：中间截断会让 01 §6.5 的示例出现末位偏差。
 */
public interface ScoreStrategy {

  /**
   * @param rawValue 录入的原始值（DIRECT/RATIO 为 0~100 的分或百分数；TARGET 为实际值；定性为 1~5）
   * @param indicator 指标快照（TARGET 取 {@code targetValue}；异常信息用 {@code code} 定位）
   * @return 百分制分（未舍入）
   */
  BigDecimal toHundred(BigDecimal rawValue, IndicatorSnapshot indicator);
}
