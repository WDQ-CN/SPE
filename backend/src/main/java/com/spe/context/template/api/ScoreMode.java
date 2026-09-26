package com.spe.context.template.api;

/**
 * 定量指标的计分方式（01 T3「计分方式」、02 §3）。
 *
 * <p>**定性指标没有计分方式**：`indicator.score_mode` 对 {@link IndicatorType#QUAL} 存 {@code null}，
 * 由 {@code type} 决定策略（12 §E2）。这样不必给 02 §3 的三值枚举加第四个值，也免掉"定性该填哪个
 * mode"的歧义——定性只有一种算法（1~5 × 20）。
 */
public enum ScoreMode {
  /** 直接打分：录入值即得分（如 0~100 的审核得分）。 */
  DIRECT,
  /** 比率：录入百分比即得分。 */
  RATIO,
  /** 目标达成率：录入实际值，按 实际值 ÷ 目标值 × 100 换算（01 §6.1）。 */
  TARGET
}
