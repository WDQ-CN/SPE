package com.spe.context.evaluation.internal.scoring.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.context.system.api.GradeThresholds;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import com.spe.context.template.api.TemplateSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ScoreCalculator} 的全边界单测（13 §E5/W2.1、§E6/W2.2；02 §7 要求"全计分方式覆盖 + 边界 + 归一化 + 等级边界"）。
 *
 * <p>权威数值样例是 01 §6.5：13 个指标 → 4 个维度分 96.35 / 94.27 / 76.00 / 88.00 → 总分 **90.82**，
 * 等级 A / A / C / B 与总分 A。夹具按 01 附录 A 的权重与计分方式搭建，**逐指标**断言换算分与贡献。
 */
class ScoreCalculatorTest {

  private static final GradeThresholds DEFAULTS = GradeThresholds.defaults();

  @Test
  @DisplayName("01 §6.5 的完整示例：13 个指标的换算分/贡献、4 个维度分与等级、总分 90.82 → A")
  void reproducesSpecExample() {
    Snapshot fixture = appendixASnapshot();
    Map<Long, Map<Long, BigDecimal>> values =
        Map.of(
            fixture.dimensionId("质量"), fixture.values(
                "IQC_PASS_RATE", "98.50",
                // 「重大质量事故」是反向指标，按 01 §6.1 的说明**由评估人人工换算**后录入得分：
                // 附录 A 的「0 次=100，1 次=60，≥2 次=0」是人工换算表，DIRECT 的录入值就是 100
                "QUALITY_ACCIDENT", "100",
                "IQC_ON_TIME_RATE", "96.00",
                "QMS_AUDIT_SCORE", "92"),
            fixture.dimensionId("交付"), fixture.values(
                "OTD_RATE", "95.08",
                "FILL_RATE", "99.20",
                "URGENT_RESPONSE", "4"),
            fixture.dimensionId("成本"), fixture.values(
                "COST_DOWN_RATE", "2.40",
                "PRICE_COMPETITIVENESS", "4",
                "PAYMENT_COOP", "3"),
            fixture.dimensionId("服务"), fixture.values(
                "ISSUE_RESPONSE", "5",
                "ABNORMAL_COOP", "4",
                "CONTACT_STABILITY", "4"));

    TaskSupplierScore result =
        ScoreCalculator.calculateTaskSupplier(fixture.snapshot(), values, DEFAULTS);

    assertThat(result.complete()).as("示例里 13 个指标都有值").isTrue();
    assertThat(scoresByCode(result.sheets()))
        .as("逐指标换算分（01 §6.5 的『换算分』列）")
        .containsEntry("IQC_PASS_RATE", new BigDecimal("98.50"))
        .containsEntry("QUALITY_ACCIDENT", new BigDecimal("100.00"))
        .containsEntry("IQC_ON_TIME_RATE", new BigDecimal("96.00"))
        .containsEntry("QMS_AUDIT_SCORE", new BigDecimal("92.00"))
        .containsEntry("OTD_RATE", new BigDecimal("97.02"))
        .containsEntry("FILL_RATE", new BigDecimal("99.20"))
        .containsEntry("URGENT_RESPONSE", new BigDecimal("80.00"))
        .containsEntry("COST_DOWN_RATE", new BigDecimal("80.00"))
        .containsEntry("PRICE_COMPETITIVENESS", new BigDecimal("80.00"))
        .containsEntry("PAYMENT_COOP", new BigDecimal("60.00"))
        .containsEntry("ISSUE_RESPONSE", new BigDecimal("100.00"))
        .containsEntry("ABNORMAL_COOP", new BigDecimal("80.00"))
        .containsEntry("CONTACT_STABILITY", new BigDecimal("80.00"));
    assertThat(contributionsByCode(result.sheets()))
        .as("逐指标贡献（01 §6.5 的『贡献』列 = 换算分 × 权重 ÷ 100）")
        .containsEntry("IQC_PASS_RATE", new BigDecimal("29.55"))
        .containsEntry("QUALITY_ACCIDENT", new BigDecimal("20.00"))
        .containsEntry("OTD_RATE", new BigDecimal("48.51"))
        .containsEntry("COST_DOWN_RATE", new BigDecimal("40.00"));

    assertThat(scoresByName(result.sheets()))
        .as("四个维度分（01 §6.5 末尾）")
        .containsEntry("质量", new BigDecimal("96.35"))
        .containsEntry("交付", new BigDecimal("94.27"))
        .containsEntry("成本", new BigDecimal("76.00"))
        .containsEntry("服务", new BigDecimal("88.00"));
    assertThat(gradesByName(result.sheets()))
        .as("维度等级：成本 C 不阻断总分 A（01 §6.3）")
        .containsEntry("质量", "A")
        .containsEntry("交付", "A")
        .containsEntry("成本", "C")
        .containsEntry("服务", "B");
    assertThat(result.totalScore()).as("总分").isEqualByComparingTo("90.82");
    assertThat(result.totalGrade()).as("总等级").isEqualTo("A");
  }

  @Test
  @DisplayName("TARGET 实际值为 0 ⇒ 得 0.00（01 §6.1a：合法，不是缺值）")
  void targetWithZeroActualValueScoresZero() {
    Snapshot fixture = appendixASnapshot();
    ScoreSheetResult sheet =
        ScoreCalculator.calculateSheet(
            fixture.dimension("交付"),
            fixture.values("OTD_RATE", "0", "FILL_RATE", "100", "URGENT_RESPONSE", "5"),
            DEFAULTS);

    assertThat(sheet.complete()).isTrue();
    assertThat(scoreOf(sheet, "OTD_RATE")).isEqualByComparingTo("0.00");
  }

  @Test
  @DisplayName("TARGET 实际值超过目标（110 ÷ 98）⇒ 按 100 封顶（01 §6.1 的 min(100, …)）")
  void targetAboveTargetIsCapped() {
    Snapshot fixture = appendixASnapshot();
    ScoreSheetResult sheet =
        ScoreCalculator.calculateSheet(
            fixture.dimension("交付"),
            fixture.values("OTD_RATE", "110", "FILL_RATE", "100", "URGENT_RESPONSE", "5"),
            DEFAULTS);

    assertThat(scoreOf(sheet, "OTD_RATE")).as("112.24% 应封顶到 100.00").isEqualByComparingTo("100.00");
  }

  @Test
  @DisplayName("RATIO 录入 120 ⇒ 按 100 封顶（01 §6.1a 的防御分支，P3 只能由单测构造）")
  void ratioAboveHundredIsCapped() {
    Snapshot fixture = appendixASnapshot();
    ScoreSheetResult sheet =
        ScoreCalculator.calculateSheet(
            fixture.dimension("质量"),
            fixture.values(
                "IQC_PASS_RATE", "120", "QUALITY_ACCIDENT", "100", "IQC_ON_TIME_RATE", "100", "QMS_AUDIT_SCORE", "100"),
            DEFAULTS);

    assertThat(scoreOf(sheet, "IQC_PASS_RATE")).isEqualByComparingTo("100.00");
    assertThat(sheet.score()).isEqualByComparingTo("100.00");
  }

  @Test
  @DisplayName("TARGET 的目标值 ≤ 0 或缺席 ⇒ 抛错（01 §6.6：发布时本该拦截，计算处不许兜底）")
  void targetWithNonPositiveTargetIsRejected() {
    IndicatorSnapshot broken =
        new IndicatorSnapshot(999L, "BROKEN_TARGET", "坏目标值", new BigDecimal("100"),
            IndicatorType.QUANT, ScoreMode.TARGET, "%", new BigDecimal("0"), null);
    DimensionSnapshot dimension =
        new DimensionSnapshot(9L, "坏维度", new BigDecimal("100"), false, 1, "质量", List.of(broken));

    assertThatThrownBy(
            () ->
                ScoreCalculator.calculateSheet(
                    dimension, Map.of(999L, new BigDecimal("10")), DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("目标值必须 > 0");
  }

  @Test
  @DisplayName("DIRECT 录入 101 ⇒ 抛错（01 §6.6 的输入边界；越界不许算出 >100 的分）")
  void directAboveHundredIsRejected() {
    Snapshot fixture = appendixASnapshot();

    assertThatThrownBy(
            () ->
                ScoreCalculator.calculateSheet(
                    fixture.dimension("质量"),
                    fixture.values(
                        "IQC_PASS_RATE", "100", "QUALITY_ACCIDENT", "101", "IQC_ON_TIME_RATE", "100", "QMS_AUDIT_SCORE", "100"),
                    DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DIRECT 录入值必须在 0~100");
  }

  @Test
  @DisplayName("定性非整数（3.5）⇒ 抛错；定性 5 ⇒ 100.00（01 §6.1a：1~5 与 20/40/60/80/100 一一对应）")
  void qualitativeMustBeIntegerBetweenOneAndFive() {
    Snapshot fixture = appendixASnapshot();
    DimensionSnapshot service = fixture.dimension("服务");

    assertThatThrownBy(
            () ->
                ScoreCalculator.calculateSheet(
                    service,
                    fixture.values("ISSUE_RESPONSE", "3.5", "ABNORMAL_COOP", "4", "CONTACT_STABILITY", "4"),
                    DEFAULTS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须是 1~5 的整数");

    ScoreSheetResult ok =
        ScoreCalculator.calculateSheet(
            service,
            fixture.values("ISSUE_RESPONSE", "5", "ABNORMAL_COOP", "5", "CONTACT_STABILITY", "5"),
            DEFAULTS);
    assertThat(scoreOf(ok, "ISSUE_RESPONSE")).isEqualByComparingTo("100.00");
    assertThat(ok.score()).isEqualByComparingTo("100.00");
  }

  @Test
  @DisplayName("13 §E6 不完整：缺一个指标 ⇒ 该维度分与等级为 null、点名缺哪个编码，完整维度照常给分且总分为 null")
  void incompleteDimensionYieldsNullScoreAndNamesMissingCodes() {
    Snapshot fixture = appendixASnapshot();
    Map<Long, Map<Long, BigDecimal>> values =
        Map.of(
            fixture.dimensionId("质量"), fixture.values(
                "IQC_PASS_RATE", "98.50",
                "IQC_ON_TIME_RATE", "96.00",
                "QMS_AUDIT_SCORE", "92"), // 故意漏掉 QUALITY_ACCIDENT
            fixture.dimensionId("交付"), fixture.values(
                "OTD_RATE", "95.08", "FILL_RATE", "99.20", "URGENT_RESPONSE", "4"),
            fixture.dimensionId("成本"), fixture.values(
                "COST_DOWN_RATE", "2.40", "PRICE_COMPETITIVENESS", "4", "PAYMENT_COOP", "3"),
            fixture.dimensionId("服务"), fixture.values(
                "ISSUE_RESPONSE", "5", "ABNORMAL_COOP", "4", "CONTACT_STABILITY", "4"));

    TaskSupplierScore result =
        ScoreCalculator.calculateTaskSupplier(fixture.snapshot(), values, DEFAULTS);

    ScoreSheetResult quality = sheetByName(result.sheets(), "质量");
    assertThat(quality.complete()).as("质量维度不完整").isFalse();
    assertThat(quality.score()).as("不完整 ⇒ 不给分（不是 0）").isNull();
    assertThat(quality.grade()).as("不完整 ⇒ 不定级").isNull();
    assertThat(quality.missingCodes()).as("要点名缺哪个编码（判据 4）").containsExactly("QUALITY_ACCIDENT");
    assertThat(sheetByName(result.sheets(), "交付").score())
        .as("完整的维度照常给分（看板才有意义）")
        .isEqualByComparingTo("94.27");
    assertThat(result.complete()).isFalse();
    assertThat(result.totalScore()).as("任一维度不完整 ⇒ 总分为 null").isNull();
    assertThat(result.totalGrade()).isNull();
  }

  @Test
  @DisplayName("舍入卡在 .005：两个 50/50 指标 33.33 与 33.34 ⇒ 33.335 按 HALF_UP 进到 33.34")
  void roundingHalfUpAtExactHalf() {
    Snapshot two = twoIndicatorSnapshot();

    ScoreSheetResult sheet =
        ScoreCalculator.calculateSheet(
            two.dimension("D"),
            two.values("A", "33.33", "B", "33.34"),
            DEFAULTS);

    assertThat(sheet.score()).as("33.335 → HALF_UP → 33.34（不是截断的 33.33）").isEqualByComparingTo("33.34");
  }

  @Test
  @DisplayName("等级边界：89.99 → B、90.00 → A；两个 50/50 指标 89.99 与 90.00 得 89.995 → 90.00 → A")
  void gradeBoundariesFollowRoundedScore() {
    assertThat(ScoreCalculator.grade(new BigDecimal("89.99"), DEFAULTS)).isEqualTo("B");
    assertThat(ScoreCalculator.grade(new BigDecimal("90.00"), DEFAULTS)).isEqualTo("A");
    assertThat(ScoreCalculator.grade(new BigDecimal("69.99"), DEFAULTS)).isEqualTo("D");
    assertThat(ScoreCalculator.grade(new BigDecimal("70.00"), DEFAULTS)).isEqualTo("C");

    Snapshot two = twoIndicatorSnapshot();
    ScoreSheetResult sheet =
        ScoreCalculator.calculateSheet(two.dimension("D"), two.values("A", "89.99", "B", "90.00"), DEFAULTS);
    assertThat(sheet.score()).isEqualByComparingTo("90.00");
    assertThat(sheet.grade()).as("按舍入后的分定级（89.995 → 90.00 → A）").isEqualTo("A");
  }

  @Test
  @DisplayName("阈值相等即停用该档：A 与 B 都设 80 时，80.00 落在 A 档（01 §6.3 允许相等）")
  void equalThresholdsDisableTheLaterGrade() {
    GradeThresholds disabledB =
        new GradeThresholds(new BigDecimal("80"), new BigDecimal("80"), new BigDecimal("70"), new BigDecimal("0"));

    assertThat(ScoreCalculator.grade(new BigDecimal("80.00"), disabledB)).isEqualTo("A");
    assertThat(ScoreCalculator.grade(new BigDecimal("79.99"), disabledB)).isEqualTo("C");
  }

  // ---------- 夹具 ----------

  private record Snapshot(TemplateSnapshot snapshot, Map<String, Long> dimensionIds, Map<String, Long> indicatorIds) {

    DimensionSnapshot dimension(String name) {
      return snapshot.dimensions().stream()
          .filter(d -> d.name().equals(name))
          .findFirst()
          .orElseThrow();
    }

    long dimensionId(String name) {
      return dimensionIds.get(name);
    }

    Map<Long, BigDecimal> values(Object... codeValuePairs) {
      Map<Long, BigDecimal> values = new LinkedHashMap<>();
      for (int i = 0; i < codeValuePairs.length; i += 2) {
        String code = (String) codeValuePairs[i];
        values.put(indicatorIds.get(code), new BigDecimal((String) codeValuePairs[i + 1]));
      }
      return values;
    }
  }

  /** 01 附录 A 的 4 维度 / 13 指标（权重、类型/方式、目标值逐条照抄）。 */
  private static Snapshot appendixASnapshot() {
    List<DimensionSpec> specs =
        List.of(
            new DimensionSpec("质量", "40",
                new IndicatorSpec("IQC_PASS_RATE", "来料批次合格率", "30", IndicatorType.QUANT, ScoreMode.RATIO, null),
                new IndicatorSpec("QUALITY_ACCIDENT", "重大质量事故", "20", IndicatorType.QUANT, ScoreMode.DIRECT, null),
                new IndicatorSpec("IQC_ON_TIME_RATE", "来料检验及时率", "20", IndicatorType.QUANT, ScoreMode.RATIO, null),
                new IndicatorSpec("QMS_AUDIT_SCORE", "质量体系审核得分", "30", IndicatorType.QUANT, ScoreMode.DIRECT, null)),
            new DimensionSpec("交付", "30",
                new IndicatorSpec("OTD_RATE", "准时交付率", "50", IndicatorType.QUANT, ScoreMode.TARGET, "98"),
                new IndicatorSpec("FILL_RATE", "订单满足率", "30", IndicatorType.QUANT, ScoreMode.RATIO, null),
                new IndicatorSpec("URGENT_RESPONSE", "紧急需求响应及时性", "20", IndicatorType.QUAL, null, null)),
            new DimensionSpec("成本", "20",
                new IndicatorSpec("COST_DOWN_RATE", "降本目标达成率", "50", IndicatorType.QUANT, ScoreMode.TARGET, "3"),
                new IndicatorSpec("PRICE_COMPETITIVENESS", "报价竞争力", "30", IndicatorType.QUAL, null, null),
                new IndicatorSpec("PAYMENT_COOP", "付款条件配合度", "20", IndicatorType.QUAL, null, null)),
            new DimensionSpec("服务", "10",
                new IndicatorSpec("ISSUE_RESPONSE", "问题响应及时性", "40", IndicatorType.QUAL, null, null),
                new IndicatorSpec("ABNORMAL_COOP", "异常处理配合度", "30", IndicatorType.QUAL, null, null),
                new IndicatorSpec("CONTACT_STABILITY", "对接人员稳定性", "30", IndicatorType.QUAL, null, null)));
    return build(specs);
  }

  /** 一个只有两个指标的维度（50/50），用于舍入与等级边界。 */
  private static Snapshot twoIndicatorSnapshot() {
    return build(
        List.of(
            new DimensionSpec("D", "100",
                new IndicatorSpec("A", "指标A", "50", IndicatorType.QUANT, ScoreMode.DIRECT, null),
                new IndicatorSpec("B", "指标B", "50", IndicatorType.QUANT, ScoreMode.DIRECT, null))));
  }

  private record IndicatorSpec(String code, String name, String weight, IndicatorType type, ScoreMode mode, String target) {}

  private record DimensionSpec(String name, String weight, IndicatorSpec... indicators) {}

  private static Snapshot build(List<DimensionSpec> specs) {
    Map<String, Long> dimensionIds = new LinkedHashMap<>();
    Map<String, Long> indicatorIds = new LinkedHashMap<>();
    List<DimensionSnapshot> dimensions = new ArrayList<>();
    long dimensionId = 1;
    long indicatorId = 100;
    for (DimensionSpec spec : specs) {
      dimensionIds.put(spec.name(), dimensionId);
      List<IndicatorSnapshot> indicators = new ArrayList<>();
      for (IndicatorSpec indicator : spec.indicators()) {
        indicatorId++;
        indicatorIds.put(indicator.code(), indicatorId);
        indicators.add(
            new IndicatorSnapshot(
                indicatorId,
                indicator.code(),
                indicator.name(),
                new BigDecimal(indicator.weight()),
                indicator.type(),
                indicator.mode(),
                "%",
                indicator.target() == null ? null : new BigDecimal(indicator.target()),
                null));
      }
      dimensions.add(
          new DimensionSnapshot(
              dimensionId, spec.name(), new BigDecimal(spec.weight()), false, (int) dimensionId, "质量", indicators));
      dimensionId++;
    }
    return new Snapshot(
        new TemplateSnapshot(7L, 3L, "季度绩效默认模板", 1, dimensions), dimensionIds, indicatorIds);
  }

  private static Map<String, BigDecimal> scoresByCode(List<ScoreSheetResult> sheets) {
    Map<String, BigDecimal> byCode = new LinkedHashMap<>();
    sheets.forEach(sheet -> sheet.indicators().forEach(i -> byCode.put(i.code(), i.scoreValue())));
    return byCode;
  }

  private static Map<String, BigDecimal> contributionsByCode(List<ScoreSheetResult> sheets) {
    Map<String, BigDecimal> byCode = new LinkedHashMap<>();
    sheets.forEach(sheet -> sheet.indicators().forEach(i -> byCode.put(i.code(), i.contribution())));
    return byCode;
  }

  private static Map<String, BigDecimal> scoresByName(List<ScoreSheetResult> sheets) {
    Map<String, BigDecimal> byName = new LinkedHashMap<>();
    sheets.forEach(sheet -> byName.put(sheet.dimensionName(), sheet.score()));
    return byName;
  }

  private static Map<String, String> gradesByName(List<ScoreSheetResult> sheets) {
    Map<String, String> byName = new LinkedHashMap<>();
    sheets.forEach(sheet -> byName.put(sheet.dimensionName(), sheet.grade()));
    return byName;
  }

  private static ScoreSheetResult sheetByName(List<ScoreSheetResult> sheets, String name) {
    return sheets.stream().filter(s -> s.dimensionName().equals(name)).findFirst().orElseThrow();
  }

  private static BigDecimal scoreOf(ScoreSheetResult sheet, String code) {
    return sheet.indicators().stream()
        .filter(i -> i.code().equals(code))
        .findFirst()
        .orElseThrow()
        .scoreValue();
  }
}
