package com.spe.context.evaluation.internal.scoring.calculator;

import com.spe.context.system.api.GradeThresholds;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.TemplateSnapshot;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计分引擎（01 §6.1/§6.2/§6.3，06 §2.5 的红线，05 ADR-4）：**纯函数门面，无 Spring、无 Mapper、无 IO**
 * （由 ArchUnit R-C 守着）。打分预览、审批试算、生效定稿三处复用同一实现——公式只存在于这里。
 *
 * <p>三条口径：
 * <ol>
 *   <li><b>除法与舍入</b>（13 §E5）：全程 {@link BigDecimal}，除法显式给 {@link MathContext}；
 *       <b>只在最终输出处</b>舍入到 2 位（HALF_UP）——中间截断会让 01 §6.5 的示例出现末位偏差。
 *   <li><b>归一化照 01 §6.2 原文</b>：维度分 = Σ(指标得分 × 指标权重) ÷ Σ指标权重；总分 = Σ(维度分 × 维度权重) ÷ Σ维度权重。
 *       发布校验已保证两个合计都是 100，所以归一化在实测中是不动点；保留它是为了"公式与规格一致"——
 *       将来校验放宽，这里不必改（13 §E6）。
 *   <li><b>不完整不猜</b>（13 §E6）：任一指标缺值 ⇒ 该维度 score/grade 为 null；任一维度不完整 ⇒ 总分/总等级为 null。
 * </ol>
 *
 * <p>越界输入一律抛 {@link IllegalArgumentException}：01 §6.6 说"换算分/维度分/总分 0~100，超界即计算缺陷"，
 * 所以这里既拦非法录入值（定性非整数、TARGET 目标 ≤ 0），也拦算出来的越界分——**响亮失败好过算出一个错数字**。
 */
public final class ScoreCalculator {

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final MathContext DIVISION = MathContext.DECIMAL128;
  private static final int SCORE_SCALE = 2;

  private ScoreCalculator() {}

  /**
   * 一张评分单（= 一个维度）试算/定稿。
   *
   * @param dimension 维度快照（含其指标）
   * @param rawValues 该评分单的原始值，键 = 指标 id；缺键或值为 null 都视为"未填"（不抛错，记不完整）
   * @param thresholds 等级阈值（由调用方从 {@code SettingsFacade} 取；Calculator 自己不读设置）
   */
  public static ScoreSheetResult calculateSheet(
      DimensionSnapshot dimension, Map<Long, BigDecimal> rawValues, GradeThresholds thresholds) {

    if (dimension.indicators().isEmpty()) {
      throw new IllegalArgumentException(
          "维度下没有任何指标，无法计分（模板发布时应已拦截）：dimensionId=" + dimension.id());
    }
    List<IndicatorScore> results = new ArrayList<>(dimension.indicators().size());
    BigDecimal weightSum = BigDecimal.ZERO;
    BigDecimal weightedSum = BigDecimal.ZERO;
    boolean complete = true;

    for (IndicatorSnapshot indicator : dimension.indicators()) {
      weightSum = weightSum.add(indicator.weight());
      BigDecimal rawValue = rawValues == null ? null : rawValues.get(indicator.id());
      if (rawValue == null) {
        complete = false;
        results.add(
            new IndicatorScore(
                indicator.id(),
                indicator.code(),
                indicator.name(),
                indicator.weight(),
                indicator.type(),
                indicator.scoreMode(),
                null,
                null,
                null));
        continue;
      }
      BigDecimal hundred = ScoreStrategies.of(indicator).toHundred(rawValue, indicator);
      requireWithinHundred(hundred, "指标换算分越界（01 §6.6）", indicator.code());
      weightedSum = weightedSum.add(hundred.multiply(indicator.weight()));
      results.add(
          new IndicatorScore(
              indicator.id(),
              indicator.code(),
              indicator.name(),
              indicator.weight(),
              indicator.type(),
              indicator.scoreMode(),
              rawValue,
              rounded(hundred),
              rounded(hundred.multiply(indicator.weight()).divide(HUNDRED, DIVISION))));
    }

    if (weightSum.signum() <= 0) {
      throw new IllegalArgumentException("维度内指标权重合计必须 > 0：dimensionId=" + dimension.id());
    }
    if (!complete) {
      return new ScoreSheetResult(
          dimension.id(), dimension.name(), dimension.weight(), results, null, null, false);
    }
    BigDecimal score = rounded(weightedSum.divide(weightSum, DIVISION));
    requireWithinHundred(score, "维度分越界（01 §6.6）", String.valueOf(dimension.id()));
    return new ScoreSheetResult(
        dimension.id(),
        dimension.name(),
        dimension.weight(),
        results,
        score,
        grade(score, thresholds),
        true);
  }

  /**
   * 一个评估单（跨全部维度）试算/定稿。
   *
   * @param snapshot 模板快照（已发布版本，05 ADR-3）
   * @param rawValuesByDimension 键 = 维度 id，值 = 该维度的原始值表；缺失的维度按"全部未填"处理
   * @param thresholds 等级阈值
   */
  public static TaskSupplierScore calculateTaskSupplier(
      TemplateSnapshot snapshot,
      Map<Long, Map<Long, BigDecimal>> rawValuesByDimension,
      GradeThresholds thresholds) {

    if (snapshot.dimensions().isEmpty()) {
      throw new IllegalArgumentException(
          "模板快照没有任何维度，无法计分（模板发布时应已拦截）：versionId=" + snapshot.versionId());
    }
    List<ScoreSheetResult> sheets =
        snapshot.dimensions().stream()
            .map(
                dimension ->
                    calculateSheet(
                        dimension,
                        rawValuesByDimension == null
                            ? null
                            : rawValuesByDimension.get(dimension.id()),
                        thresholds))
            .toList();

    boolean complete = sheets.stream().allMatch(ScoreSheetResult::complete);
    if (!complete) {
      return new TaskSupplierScore(sheets, null, null, false);
    }
    BigDecimal weightSum = BigDecimal.ZERO;
    BigDecimal weightedSum = BigDecimal.ZERO;
    for (ScoreSheetResult sheet : sheets) {
      // 用**已舍入的维度分**加权：与 01 §6.5 的示例算式一致（示例里的 96.35/94.27 就是 2 位分）
      weightedSum = weightedSum.add(sheet.score().multiply(sheet.weight()));
      weightSum = weightSum.add(sheet.weight());
    }
    if (weightSum.signum() <= 0) {
      throw new IllegalArgumentException(
          "维度权重合计必须 > 0：versionId=" + snapshot.versionId());
    }
    BigDecimal total = rounded(weightedSum.divide(weightSum, DIVISION));
    requireWithinHundred(total, "总分越界（01 §6.6）", "versionId=" + snapshot.versionId());
    return new TaskSupplierScore(sheets, total, grade(total, thresholds), true);
  }

  /**
   * 定级（01 §6.3）：A ≥ a、B ≥ b、C ≥ c、其余 D；阈值相等即"停用该档"（可能返回更靠前的那档）。
   *
   * <p>用**舍入后的 2 位分**定级（例：89.995 → 90.00 → A），与展示口径一致。
   */
  public static String grade(BigDecimal score, GradeThresholds thresholds) {
    if (score.compareTo(thresholds.a()) >= 0) {
      return "A";
    }
    if (score.compareTo(thresholds.b()) >= 0) {
      return "B";
    }
    if (score.compareTo(thresholds.c()) >= 0) {
      return "C";
    }
    return "D";
  }

  private static BigDecimal rounded(BigDecimal value) {
    return value.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
  }

  private static void requireWithinHundred(BigDecimal value, String message, String where) {
    if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(HUNDRED) > 0) {
      throw new IllegalArgumentException(message + "：value=" + value + "，at=" + where);
    }
  }
}
