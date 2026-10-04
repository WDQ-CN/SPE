package com.spe.context.evaluation.internal.scoring.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import com.spe.context.system.api.GradeThresholds;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 兑现 M2 的遗留（13 §E4）：{@code TemplateSnapshotFacade} 输出的快照**真的被 {@code ScoreCalculator} 消费**——
 * 用**库里真实的默认模板**（V8 种子 + V9 回填的档位文案）跑一遍 01 §6.5 的示例，而不是用手搭的夹具。
 *
 * <p>这条用例同时锁住三件事：
 * <ol>
 *   <li>快照契约可用：4 个维度 40/30/20/10、13 个指标、维度内权重合计 100（12 §E16 的契约字段够不够算分）；
 *   <li>指标**编码**与 01 附录 A 一致（Excel 导入与开放 API 都按编码定位，01 T3/E3）；
 *   <li>计分口径在真实数据上复现 01 §6.5 的 90.82（夹具再像也不能替代真数据）。
 * </ol>
 */
class ScoreCalculatorDefaultTemplateIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TemplateSnapshotFacade templateSnapshotFacade;

  @Test
  @DisplayName("真实默认模板快照 → 01 §6.5 示例算出 90.82 / 维度 A·A·C·B（兑现 M2 遗留：快照被计算器消费）")
  void calculatesSpecExampleOnTheRealDefaultTemplate() {
    long publishedVersionId = publishedDefaultVersionId();
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(publishedVersionId);

    assertThat(snapshot.dimensions()).as("默认模板是 4 个维度").hasSize(4);
    assertThat(snapshot.dimensions())
        .extracting(DimensionSnapshot::name, d -> d.weight().toPlainString())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("质量", "40.00"),
            org.assertj.core.groups.Tuple.tuple("交付", "30.00"),
            org.assertj.core.groups.Tuple.tuple("成本", "20.00"),
            org.assertj.core.groups.Tuple.tuple("服务", "10.00"));
    assertThat(snapshot.dimensions().stream().mapToInt(d -> d.indicators().size()).sum())
        .as("附录 A 共 13 个指标")
        .isEqualTo(13);
    assertThat(snapshot.dimensions())
        .allSatisfy(
            dimension ->
                assertThat(dimension.indicators().stream().map(IndicatorSnapshot::weight).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("维度内权重合计 = 100（01 T4）")
                    .isEqualByComparingTo("100"));

    Map<Long, Map<Long, BigDecimal>> values = new LinkedHashMap<>();
    values.put(dimensionId(snapshot, "质量"), valuesOf(snapshot, "质量",
        "IQC_PASS_RATE", "98.50", "QUALITY_ACCIDENT", "100", "IQC_ON_TIME_RATE", "96.00", "QMS_AUDIT_SCORE", "92"));
    values.put(dimensionId(snapshot, "交付"), valuesOf(snapshot, "交付",
        "OTD_RATE", "95.08", "FILL_RATE", "99.20", "URGENT_RESPONSE", "4"));
    values.put(dimensionId(snapshot, "成本"), valuesOf(snapshot, "成本",
        "COST_DOWN_RATE", "2.40", "PRICE_COMPETITIVENESS", "4", "PAYMENT_COOP", "3"));
    values.put(dimensionId(snapshot, "服务"), valuesOf(snapshot, "服务",
        "ISSUE_RESPONSE", "5", "ABNORMAL_COOP", "4", "CONTACT_STABILITY", "4"));

    TaskSupplierScore result =
        ScoreCalculator.calculateTaskSupplier(snapshot, values, GradeThresholds.defaults());

    assertThat(result.complete()).as("13 个指标都有值").isTrue();
    assertThat(result.sheets())
        .extracting(ScoreSheetResult::dimensionName, ScoreSheetResult::score, ScoreSheetResult::grade)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("质量", new BigDecimal("96.35"), "A"),
            org.assertj.core.groups.Tuple.tuple("交付", new BigDecimal("94.27"), "A"),
            org.assertj.core.groups.Tuple.tuple("成本", new BigDecimal("76.00"), "C"),
            org.assertj.core.groups.Tuple.tuple("服务", new BigDecimal("88.00"), "B"));
    assertThat(result.totalScore()).as("总分（01 §6.5）").isEqualByComparingTo("90.82");
    assertThat(result.totalGrade()).isEqualTo("A");
  }

  @Test
  @DisplayName("只认已发布版本：草稿与不存在的 id 都拿不到快照，而已发布版本拿得到（05 ADR-3）")
  void onlyPublishedVersionsHaveSnapshots() {
    List<Long> draftIds =
        jdbcTemplate.queryForList(
            "select v.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.status = 'DRAFT'",
            Long.class);
    assertThat(draftIds).as("每个草稿都必须拿不到快照").allSatisfy(id -> assertThat(templateSnapshotFacade.findPublished(id)).isEmpty());
    assertThat(templateSnapshotFacade.findPublished(999_999L)).as("不存在的版本").isEmpty();
    // 正例断言：否则"库是空的"也会让上面两条恒真（CLAUDE「验证命令的判据形状」）
    assertThat(templateSnapshotFacade.findPublished(publishedDefaultVersionId()))
        .as("已发布版本必须拿得到快照")
        .isPresent();
  }

  private long publishedDefaultVersionId() {
    Long id =
        jdbcTemplate.queryForObject(
            "select v.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'"
                + " order by v.id limit 1",
            Long.class);
    assertThat(id).as("V8 种子的默认模板 v1 必须存在且已发布").isNotNull();
    return id;
  }

  private static long dimensionId(TemplateSnapshot snapshot, String name) {
    return snapshot.dimensions().stream()
        .filter(d -> d.name().equals(name))
        .findFirst()
        .orElseThrow()
        .id();
  }

  private static Map<Long, BigDecimal> valuesOf(
      TemplateSnapshot snapshot, String dimensionName, String... codeValuePairs) {
    List<IndicatorSnapshot> indicators =
        snapshot.dimensions().stream()
            .filter(d -> d.name().equals(dimensionName))
            .findFirst()
            .orElseThrow()
            .indicators();
    Map<Long, BigDecimal> values = new LinkedHashMap<>();
    for (int i = 0; i < codeValuePairs.length; i += 2) {
      String code = codeValuePairs[i];
      IndicatorSnapshot indicator =
          indicators.stream()
              .filter(x -> x.code().equals(code))
              .findFirst()
              .orElseThrow(() -> new AssertionError("默认模板里没有这个编码：" + code + "（附录 A 与 V8 不一致？）"));
      values.put(indicator.id(), new BigDecimal(codeValuePairs[i + 1]));
    }
    return values;
  }
}
