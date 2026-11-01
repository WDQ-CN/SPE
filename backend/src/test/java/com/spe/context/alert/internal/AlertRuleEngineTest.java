package com.spe.context.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.spe.context.system.api.AlertRules;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 预警规则引擎（M5 W2）：三条规则 + 跨期口径的纯函数用例。 */
class AlertRuleEngineTest {

  private static final AlertRules RULES = AlertRules.defaults();

  private static AlertRuleEngine.DimensionInput dimension(
      long id, String name, String score, boolean key) {
    return new AlertRuleEngine.DimensionInput(
        id, name, score == null ? null : new BigDecimal(score), key);
  }

  private static AlertRuleEngine.PeriodInput period(int year, int quarter, String score) {
    return new AlertRuleEngine.PeriodInput(year, quarter, new BigDecimal(score));
  }

  private static AlertRuleEngine.Evaluation evaluation(
      String total, List<AlertRuleEngine.DimensionInput> dimensions,
      List<AlertRuleEngine.PeriodInput> history, AlertRules rules) {
    return new AlertRuleEngine.Evaluation(
        1L,
        2L,
        "供应商甲",
        2026,
        2,
        total == null ? null : new BigDecimal(total),
        dimensions,
        history,
        true,
        rules);
  }

  @Test
  @DisplayName("规则①：总分低于阈值才触发；等于阈值不触发")
  void totalRule() {
    List<AlertRuleEngine.Draft> below =
        AlertRuleEngine.evaluate(evaluation("69.99", List.of(), List.of(), RULES));
    assertThat(below).extracting(AlertRuleEngine.Draft::type).containsExactly(AlertTypes.TOTAL_LOW);
    assertThat(below.get(0).content()).contains("69.99", "低于阈值 70", "2026 Q2");
    assertThat(below.get(0).dimensionId()).as("总分预警不绑定维度").isNull();
    assertThat(below.get(0).baselineScore()).isEqualByComparingTo("69.99");
    assertThat(below.get(0).targetScore()).as("D4：总分类目标=总分阈值").isEqualByComparingTo("70");

    assertThat(AlertRuleEngine.evaluate(evaluation("70", List.of(), List.of(), RULES))).isEmpty();
  }

  @Test
  @DisplayName("规则②：每个关键维度各一条；非关键维度不触发；分数为空跳过")
  void keyDimensionRule() {
    List<AlertRuleEngine.Draft> drafts =
        AlertRuleEngine.evaluate(
            evaluation(
                "90",
                List.of(
                    dimension(11, "质量", "69.5", true),
                    dimension(12, "交付", "80", true),
                    dimension(13, "成本", "10", false),
                    dimension(14, "服务", null, true)),
                List.of(),
                RULES));
    assertThat(drafts).extracting(AlertRuleEngine.Draft::type)
        .containsExactly(AlertTypes.KEY_DIMENSION_LOW);
    assertThat(drafts.get(0).content()).contains("质量", "69.5", "低于阈值 70");
    assertThat(drafts.get(0).dimensionId()).as("D4/D8：关键维度预警必须带维度 id").isEqualTo(11L);
    assertThat(drafts.get(0).baselineScore()).isEqualByComparingTo("69.5");
    assertThat(drafts.get(0).targetScore()).as("D4：关键维度目标=维度阈值").isEqualByComparingTo("70");
  }

  @Test
  @DisplayName("规则③ N=2：三个日历相邻季度连续下降且累计 ≥5 才触发（历史可乱序）")
  void trendRuleRequiresThreeAdjacentDeclines() {
    List<AlertRuleEngine.Draft> drafts =
        AlertRuleEngine.evaluate(
            evaluation(
                "79",
                List.of(),
                List.of(period(2025, 4, "90.00"), period(2026, 1, "85.00")),
                RULES));
    assertThat(drafts).extracting(AlertRuleEngine.Draft::type)
        .containsExactly(AlertTypes.TREND_DECLINE);
    assertThat(drafts.get(0).content()).contains("累计下降 11", "2025 Q4", "2026 Q2");
    assertThat(drafts.get(0).dimensionId()).isNull();
    assertThat(drafts.get(0).baselineScore()).as("D4：趋势类基线=本期总分").isEqualByComparingTo("79");
    assertThat(drafts.get(0).targetScore()).as("D4：趋势类目标=不得继续下降").isEqualByComparingTo("79");
  }

  @Test
  @DisplayName("规则③：跳期即重置（缺 2026Q1 不触发）")
  void trendRuleResetsOnGap() {
    assertThat(
            AlertRuleEngine.evaluate(
                evaluation(
                    "79",
                    List.of(),
                    List.of(period(2025, 4, "90.00"), period(2026, 2, "80.00")),
                    RULES)))
        .isEmpty();
  }

  @Test
  @DisplayName("规则③：任一步未下降（持平/回升）不触发")
  void trendRuleRequiresStrictDecline() {
    assertThat(
            AlertRuleEngine.evaluate(
                evaluation(
                    "85",
                    List.of(),
                    List.of(period(2025, 4, "90.00"), period(2026, 1, "85.00")),
                    RULES)))
        .isEmpty();
    assertThat(
            AlertRuleEngine.evaluate(
                evaluation(
                    "80",
                    List.of(),
                    List.of(period(2025, 4, "90.00"), period(2026, 1, "85.00")),
                    new AlertRules(true, 70, 70, 2, 11))))
        .as("累计 10 < 11 不触发")
        .isEmpty();
  }

  @Test
  @DisplayName("规则③ N=1：两个相邻季度下降且累计达标即触发（跨年 Q4→Q1）")
  void trendRuleWithSingleStepAcrossYear() {
    AlertRuleEngine.Evaluation evaluation =
        new AlertRuleEngine.Evaluation(
            1L,
            2L,
            "供应商甲",
            2026,
            1,
            new BigDecimal("83"),
            List.of(),
            List.of(period(2025, 4, "90")),
            true,
            new AlertRules(true, 70, 70, 1, 5));
    assertThat(AlertRuleEngine.evaluate(evaluation)).extracting(AlertRuleEngine.Draft::type)
        .containsExactly(AlertTypes.TREND_DECLINE);
  }

  @Test
  @DisplayName("非本期首次生效：趋势规则不参与判定（01 W2）")
  void trendSuppressedWhenNotFirstEffective() {
    AlertRuleEngine.Evaluation evaluation =
        new AlertRuleEngine.Evaluation(
            1L,
            2L,
            "供应商甲",
            2026,
            2,
            new BigDecimal("79"),
            List.of(),
            List.of(period(2025, 4, "90"), period(2026, 1, "85")),
            false,
            RULES);
    assertThat(AlertRuleEngine.evaluate(evaluation)).as("重评再定稿不得再次生成趋势预警").isEmpty();
  }

  @Test
  @DisplayName("规则关闭：不产出任何草稿")
  void disabledRulesProduceNothing() {
    assertThat(
            AlertRuleEngine.evaluate(
                evaluation(
                    "10",
                    List.of(dimension(11, "质量", "10", true)),
                    List.of(period(2025, 4, "90")),
                    new AlertRules(false, 70, 70, 2, 5))))
        .isEmpty();
  }
}
