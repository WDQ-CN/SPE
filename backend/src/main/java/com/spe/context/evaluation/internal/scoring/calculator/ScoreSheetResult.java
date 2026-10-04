package com.spe.context.evaluation.internal.scoring.calculator;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一张评分单（= 一个维度）的结果（13 §E5 / §E6）。
 *
 * <p>⚠️ {@code complete = false} 时 {@code score} 与 {@code grade} 都是 <b>null</b>：
 * 不完整**不重新归一化**（那会让"填了一个指标"虚高成满分），也不按 0 计（那会把"还没填"显示成"考得差"）。
 * 已完整的维度照常给分与定级——看板上"质量 96.35 / 交付未完成"才是有意义的信息（13 §E6）。
 *
 * @param dimensionId 维度 id
 * @param dimensionName 维度名称
 * @param weight 模板内权重（0~100）
 * @param indicators 逐指标结果（按快照顺序，即 {@code sort_no}）
 * @param score 维度分（2 位，HALF_UP）；不完整时为 null
 * @param grade 维度等级 A/B/C/D（按 2 位后的分数定级）；不完整时为 null
 * @param complete 是否该维度全部指标都已填
 */
public record ScoreSheetResult(
    long dimensionId,
    String dimensionName,
    BigDecimal weight,
    List<IndicatorScore> indicators,
    BigDecimal score,
    String grade,
    boolean complete) {

  /**
   * 未填指标的编码，按快照顺序。提交被拒时用它指明"缺哪几个"（01 E4 / 13 §1 判据 4）；
   * 全部已填时是空列表。
   */
  public java.util.List<String> missingCodes() {
    return indicators.stream().filter(indicator -> !indicator.filled()).map(IndicatorScore::code).toList();
  }
}
