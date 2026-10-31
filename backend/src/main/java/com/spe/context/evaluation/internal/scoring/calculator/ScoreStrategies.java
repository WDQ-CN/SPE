package com.spe.context.evaluation.internal.scoring.calculator;

import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 换算策略表（13 §E5）：DIRECT / RATIO / TARGET 三个定量策略 + 定性单独一格。
 *
 * <p>口径全部来自 01 §6.1 与 §6.1a；**这里只做换算，不做输入边界的错误码映射**——越界一律抛
 * {@link IllegalArgumentException}（"计算缺陷/非法输入"要响亮失败，不能算出一个看似合理的错数字），
 * 由服务层按 06 §2.6 映射成 2xxx。
 */
public final class ScoreStrategies {

  private static final BigDecimal ZERO = new BigDecimal("0");
  private static final BigDecimal ONE = new BigDecimal("1");
  private static final BigDecimal FIVE = new BigDecimal("5");
  private static final BigDecimal TWENTY = new BigDecimal("20");

  /** 除法一律显式给 MathContext（06 §2.5 的数值红线：禁止 double、除法必须有精度口径）。 */
  private static final MathContext DIVISION = MathContext.DECIMAL128;

  /** DIRECT（01 §6.1）：录入值即得分；边界 0~100（01 §6.6 由后端拒绝越界）。 */
  static final ScoreStrategy DIRECT =
      (rawValue, indicator) -> {
        requireScaleAtMostTwo(rawValue, "DIRECT 录入值格式不合法", indicator);
        requireRange(rawValue, ZERO, ScoreCalculator.HUNDRED, "DIRECT 录入值必须在 0~100", indicator);
        return rawValue;
      };

  /**
   * RATIO（01 §6.1）：原值即得分，**>100 按 100 计**。
   *
   * <p>封顶是 01 §6.1a 的**防御分支**：录入时本该被拒绝，但它要为「历史数据经开放 API 进入」兜底。
   * P3 没有 API（M6 才做），所以这条分支在 P3 只能由单测直接构造——见
   * {@code ScoreCalculatorTest.ratioAboveHundredIsCapped}。
   */
  static final ScoreStrategy RATIO =
      (rawValue, indicator) -> {
        requireScaleAtMostTwo(rawValue, "RATIO 录入值格式不合法", indicator);
        requireNonNegative(rawValue, "RATIO 录入值不能为负", indicator);
        return rawValue.min(ScoreCalculator.HUNDRED);
      };

  /** TARGET（01 §6.1）：{@code min(100, 实际值 ÷ 目标值 × 100)}；目标值必须 > 0（01 §6.6）。 */
  static final ScoreStrategy TARGET =
      (rawValue, indicator) -> {
        BigDecimal target = indicator.targetValue();
        if (target == null || target.signum() <= 0) {
          throw new IllegalArgumentException(
              "TARGET 指标的目标值必须 > 0（模板发布时应当已拦截）：code="
                  + indicator.code()
                  + "，target="
                  + target);
        }
        requireScaleAtMostTwo(rawValue, "TARGET 实际值格式不合法", indicator);
        requireNonNegative(rawValue, "TARGET 实际值不能为负", indicator);
        return rawValue.multiply(ScoreCalculator.HUNDRED).divide(target, DIVISION).min(ScoreCalculator.HUNDRED);
      };

  /** 定性（01 §6.1）：1~5 的整数 × 20；不设中间值（01 §6.1a）。 */
  static final ScoreStrategy QUALITATIVE =
      (rawValue, indicator) -> {
        boolean integral = rawValue.stripTrailingZeros().scale() <= 0;
        if (!integral || rawValue.compareTo(ONE) < 0 || rawValue.compareTo(FIVE) > 0) {
          throw new IllegalArgumentException(
              "定性指标必须是 1~5 的整数（不设中间值）：code=" + indicator.code() + "，raw=" + rawValue);
        }
        return rawValue.multiply(TWENTY);
      };

  private ScoreStrategies() {}

  /**
   * 取该指标对应的策略：**先看 type 再看 scoreMode**。
   *
   * <p>顺序有语义：定性指标的 {@code scoreMode} 按 12 §E2 存 null，若先看 scoreMode 就会在
   * "定性却没填方式"的合法数据上抛错。
   */
  public static ScoreStrategy of(IndicatorSnapshot indicator) {
    if (indicator.type() == IndicatorType.QUAL) {
      if (indicator.scoreMode() != null) {
        throw new IllegalArgumentException(
            "定性指标不应有 scoreMode（12 §E2）：code=" + indicator.code() + "，mode=" + indicator.scoreMode());
      }
      return QUALITATIVE;
    }
    ScoreMode mode = indicator.scoreMode();
    if (mode == null) {
      throw new IllegalArgumentException("定量指标必须有 scoreMode（12 §E2）：code=" + indicator.code());
    }
    return switch (mode) {
      case DIRECT -> DIRECT;
      case RATIO -> RATIO;
      case TARGET -> TARGET;
    };
  }

  /**
   * 输入精度：最多两位小数（01 §6.6 的"最多 2 位小数"，四种计分方式一致）。
   *
   * <p>放在策略里而不是各 Service 里：手工暂存与 Excel 导入都会走策略，规则只写一份（CLAUDE 默认约定第 3 条）。
   * 越界抛 {@link IllegalArgumentException}，由调用方翻成 2025 SCORE_VALUE_INVALID。
   */
  private static void requireScaleAtMostTwo(
      BigDecimal value, String message, IndicatorSnapshot indicator) {
    if (value.stripTrailingZeros().scale() > 2) {
      throw new IllegalArgumentException(
          message + "（最多两位小数）：code=" + indicator.code() + "，raw=" + value);
    }
  }

  private static void requireNonNegative(BigDecimal value, String message, IndicatorSnapshot indicator) {
    if (value.signum() < 0) {
      throw new IllegalArgumentException(message + "：code=" + indicator.code() + "，raw=" + value);
    }
  }

  private static void requireRange(
      BigDecimal value, BigDecimal min, BigDecimal max, String message, IndicatorSnapshot indicator) {
    if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw new IllegalArgumentException(message + "：code=" + indicator.code() + "，raw=" + value);
    }
  }
}
