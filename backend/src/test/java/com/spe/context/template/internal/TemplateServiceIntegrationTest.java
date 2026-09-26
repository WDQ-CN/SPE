package com.spe.context.template.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import com.spe.context.template.api.TemplateVersionStatus;
import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * template 上下文的集成测试（12 §3-W2）：跑在 Testcontainers 的真实 MySQL 上，迁移 V1~V8 已应用。
 *
 * <p>类级 `@Transactional` 让每个用例结束后回滚，不污染共享的容器库——但**并发用例不能这样做**
 * （其它线程看不到未提交的数据），所以它们单独放在 {@code TemplateConcurrencyIntegrationTest}。
 */
@Transactional
class TemplateServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TemplateService templateService;
  @Autowired private TemplateSnapshotFacade snapshotFacade;

  // ---------------------------------------------------------------- 契约：对照 01 附录 A

  @Test
  @DisplayName("契约：V8 种子的发布版本快照与附录 A 逐条一致（4 维度 + 13 指标，编码与权重全列）")
  void defaultTemplateMatchesAppendixA() {
    long versionId = publishedDefaultVersionId();

    TemplateSnapshot snapshot = snapshotFacade.requirePublished(versionId);

    assertThat(snapshot.templateName()).isEqualTo("季度绩效默认模板");
    assertThat(snapshot.versionNo()).isEqualTo(1);
    assertThat(snapshot.dimensions()).hasSize(4);

    // 维度：权重与建议评估部门（后者是 P3 自动指派的依据，01 J2）
    assertThat(snapshot.dimensions())
        .extracting(DimensionSnapshot::weight)
        .containsExactly(
            new BigDecimal("40.00"), new BigDecimal("30.00"), new BigDecimal("20.00"), new BigDecimal("10.00"));
    assertThat(snapshot.dimensions())
        .extracting(DimensionSnapshot::suggestedDept)
        .containsExactly("质量", "交付物流", "采购", "服务");
    assertThat(snapshot.dimensions()).allSatisfy(d -> assertThat(d.isKey()).isFalse());

    // 指标：13 个，**(code, weight, unit) 逐条全列**。
    // ⚠️ V8 是直插 PUBLISHED、**绕过了服务层的发布校验**，所以种子数据的正确性没有第二道守卫——
    // 把 FILL_RATE 抄成 50（维度内合计变 130）或把 unit 全留空，此处不红就没人会发现（出口判据 1 的原文理由）。
    Map<String, IndicatorSnapshot> byCode =
        snapshot.dimensions().stream()
            .flatMap(d -> d.indicators().stream())
            .collect(Collectors.toMap(IndicatorSnapshot::code, Function.identity()));
    assertThat(byCode).hasSize(13);
    assertThat(byCode.keySet())
        .containsExactlyInAnyOrder(
            "IQC_PASS_RATE", "QUALITY_ACCIDENT", "IQC_ON_TIME_RATE", "QMS_AUDIT_SCORE",
            "OTD_RATE", "FILL_RATE", "URGENT_RESPONSE",
            "COST_DOWN_RATE", "PRICE_COMPETITIVENESS", "PAYMENT_COOP",
            "ISSUE_RESPONSE", "ABNORMAL_COOP", "CONTACT_STABILITY");

    Map<String, String> expectedWeights =
        Map.ofEntries(
            Map.entry("IQC_PASS_RATE", "30"), Map.entry("QUALITY_ACCIDENT", "20"),
            Map.entry("IQC_ON_TIME_RATE", "20"), Map.entry("QMS_AUDIT_SCORE", "30"),
            Map.entry("OTD_RATE", "50"), Map.entry("FILL_RATE", "30"),
            Map.entry("URGENT_RESPONSE", "20"), Map.entry("COST_DOWN_RATE", "50"),
            Map.entry("PRICE_COMPETITIVENESS", "30"), Map.entry("PAYMENT_COOP", "20"),
            Map.entry("ISSUE_RESPONSE", "40"), Map.entry("ABNORMAL_COOP", "30"),
            Map.entry("CONTACT_STABILITY", "30"));
    expectedWeights.forEach(
        (code, weight) ->
            assertThat(byCode.get(code).weight())
                .as("指标 %s 的权重（附录 A）", code)
                .isEqualByComparingTo(weight));

    // unit：附录 A 没有这一列，四类取值是按它的「说明」列推导的（12 §E15），同样是"没人守就会漂"的地方
    Map<String, String> expectedUnits =
        Map.ofEntries(
            Map.entry("IQC_PASS_RATE", "%"), Map.entry("QUALITY_ACCIDENT", "次"),
            Map.entry("IQC_ON_TIME_RATE", "%"), Map.entry("QMS_AUDIT_SCORE", "分"),
            Map.entry("OTD_RATE", "%"), Map.entry("FILL_RATE", "%"),
            Map.entry("URGENT_RESPONSE", "档"), Map.entry("COST_DOWN_RATE", "%"),
            Map.entry("PRICE_COMPETITIVENESS", "档"), Map.entry("PAYMENT_COOP", "档"),
            Map.entry("ISSUE_RESPONSE", "档"), Map.entry("ABNORMAL_COOP", "档"),
            Map.entry("CONTACT_STABILITY", "档"));
    expectedUnits.forEach(
        (code, unit) ->
            assertThat(byCode.get(code).unit()).as("指标 %s 的单位", code).isEqualTo(unit));

    // 计分方式：TARGET 的目标值按百分数数值记（与 01 §6.1 的公式同口径）
    assertThat(byCode.values().stream().filter(i -> i.scoreMode() == ScoreMode.TARGET).map(IndicatorSnapshot::code))
        .containsExactlyInAnyOrder("OTD_RATE", "COST_DOWN_RATE");
    assertThat(byCode.get("OTD_RATE").targetValue()).isEqualByComparingTo("98");
    assertThat(byCode.get("COST_DOWN_RATE").targetValue()).isEqualByComparingTo("3");

    // 定性指标没有计分方式（策略由 type 决定）；定性档位文案只有 URGENT_RESPONSE 有（附录 A 只给了它）
    assertThat(byCode.values().stream().filter(i -> i.type() == IndicatorType.QUAL))
        .hasSize(6)
        .allSatisfy(i -> assertThat(i.scoreMode()).isNull());
    assertThat(byCode.get("URGENT_RESPONSE").standard()).contains("4 小时内响应");
    assertThat(byCode.get("ISSUE_RESPONSE").standard()).isNull();

    // DIRECT 指标的换算表必须落进 standard，否则 P3 会把它当普通 0~100 直接打分（12 §E15 的 R-2）
    assertThat(byCode.get("QUALITY_ACCIDENT").standard()).isEqualTo("0 次=100，1 次=60，≥2 次=0");
    assertThat(byCode.get("QMS_AUDIT_SCORE").standard()).isEqualTo("0~100");

    // 把 W1 手工跑过的那两条权重判据变成用例：维度合计 100、**每个维度内**指标合计 100
    BigDecimal dimensionSum =
        jdbcTemplate.queryForObject(
            "SELECT SUM(weight) FROM dimension WHERE template_version_id = ?", BigDecimal.class, versionId);
    assertThat(dimensionSum).as("维度权重合计").isEqualByComparingTo("100");
    List<BigDecimal> perDimensionSums =
        jdbcTemplate.queryForList(
            "SELECT SUM(i.weight) FROM dimension d JOIN indicator i ON i.dimension_id = d.id"
                + " WHERE d.template_version_id = ? GROUP BY d.id",
            BigDecimal.class,
            versionId);
    assertThat(perDimensionSums)
        .as("每个维度内的指标权重合计")
        .hasSize(4)
        .allSatisfy(sum -> assertThat(sum).isEqualByComparingTo("100"));
  }

  @Test
  @DisplayName("契约：草稿取不到快照——计分口径必须来自冻结过的数据（05 ADR-3）")
  void draftHasNoSnapshot() {
    long versionId = publishedDefaultVersionId();
    // 编辑已发布版本 → 服务端复制出新草稿；那个草稿不该有快照
    EffectiveVersion draft = templateService.ensureEditable(versionId);

    assertThat(draft.copied()).isTrue();
    assertThat(snapshotFacade.findPublished(draft.versionId())).isEmpty();
    assertThatThrownBy(() -> snapshotFacade.requirePublished(draft.versionId()))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_VERSION_NOT_FOUND);
  }

  // ---------------------------------------------------------------- 复制语义（12 §E5）

  @Test
  @DisplayName("已发布版本被改 → 复制出新草稿，原版本内容与状态不变，且旧行 id 能映射到新行")
  void editingPublishedVersionCopiesTreeAndKeepsOriginal() {
    long sourceVersionId = publishedDefaultVersionId();
    List<Dimension> sourceDimensions = snapshotDimensions(sourceVersionId);

    EffectiveVersion effective = templateService.ensureEditable(sourceVersionId);

    assertThat(effective.copied()).isTrue();
    assertThat(effective.versionId()).isNotEqualTo(sourceVersionId);
    // 原版本仍是 PUBLISHED、维度数未变
    assertThat(versionStatus(sourceVersionId)).isEqualTo(TemplateVersionStatus.PUBLISHED);
    assertThat(snapshotDimensions(sourceVersionId)).hasSize(sourceDimensions.size());
    // 新草稿是 DRAFT，且记下了复制来源
    assertThat(versionStatus(effective.versionId())).isEqualTo(TemplateVersionStatus.DRAFT);
    assertThat(copiedFrom(effective.versionId())).isEqualTo(sourceVersionId);
    // 维度与指标都被复制，映射可用
    assertThat(snapshotDimensions(effective.versionId())).hasSize(sourceDimensions.size());
    assertThat(effective.dimensionIdMap()).hasSize(sourceDimensions.size());
    assertThat(effective.indicatorIdMap()).hasSize(13);
    assertThat(effective.requireDimensionId(sourceDimensions.get(0).getId(), sourceVersionId))
        .isNotEqualTo(sourceDimensions.get(0).getId());
  }

  @Test
  @DisplayName("并发复用：第二次 ensureEditable 直接复用同源草稿，不再复制第二份（12 §E12）")
  void secondEnsureEditableReusesDraft() {
    long sourceVersionId = publishedDefaultVersionId();

    EffectiveVersion first = templateService.ensureEditable(sourceVersionId);
    EffectiveVersion second = templateService.ensureEditable(sourceVersionId);

    assertThat(second.versionId()).isEqualTo(first.versionId());
    assertThat(second.copied()).isFalse();
    long draftCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM template_version WHERE copied_from_version_id = ? AND status = 'DRAFT'",
            Long.class,
            sourceVersionId);
    assertThat(draftCount).isEqualTo(1L);
  }

  // ---------------------------------------------------------------- 删维度不留孤儿（12 §E13）

  @Test
  @DisplayName("删维度：其指标在同一事务内一并删除，不留孤儿（否则复制会带着它们跑、校验却看不见）")
  void deletingDimensionRemovesItsIndicators() {
    // 在默认模板上开一个草稿，然后删掉第一个维度
    long versionId = templateService.ensureEditable(publishedDefaultVersionId()).versionId();
    Dimension dimension = snapshotDimensions(versionId).get(0);
    long indicatorsBefore = countIndicators(dimension.getId());
    assertThat(indicatorsBefore).isPositive();

    templateService.deleteDimension(dimension.getId(), dimension.getVersion());

    assertThat(countIndicators(dimension.getId())).isZero();
    Long orphanIndicators =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM indicator i WHERE i.template_version_id = ?"
                + " AND NOT EXISTS (SELECT 1 FROM dimension d WHERE d.id = i.dimension_id)",
            Long.class,
            versionId);
    assertThat(orphanIndicators).isZero();
  }

  // ---------------------------------------------------------------- 发布校验（真实库口径）

  @Test
  @DisplayName("发布：草稿权重合计不为 100 时被拒（2011），并给出可定位的报文")
  void publishRejectsWeightNotFullOnRealData() {
    long versionId = templateService.ensureEditable(publishedDefaultVersionId()).versionId();
    Dimension dimension = snapshotDimensions(versionId).get(0);
    TemplateVersion draft = requireVersion(versionId);

    // 把第一个维度从 40 改成 30 → 维度合计 90
    templateService.updateDimension(
        dimension.getId(), dimension.getName(), new BigDecimal("30.00"), dimension.getSortNo(),
        dimension.getSuggestedDept(), Boolean.TRUE.equals(dimension.getIsKey()), dimension.getVersion());

    assertThatThrownBy(() -> templateService.publish(versionId, draft.getVersion()))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_WEIGHT_INVALID);
  }

  // ---------------------------------------------------------------- 2013 的 SQL 判定（W5 补）

  /**
   * W5 核实出口判据 2 时发现的缺口：2013 原先**只有一条 mock 掉 `findInvalidTargets` 的单测**——
   * 它验的是"服务层拿到非空列表就抛 2013"，而「**目标值缺失或 ≤ 0**」这个判定本身写在 SQL 里，
   * 从未在真实数据上跑过。这三条把它补上：两个分支各一条 + 一条边界。
   *
   * <p>这里直接改库、不走 `updateIndicator`：要验的正是 SQL 判定；而且那条服务方法要求把**全部字段**
   * 原样传一遍，我第一版就随手把权重填成了 100（原值 50），于是"交付"维度合计变成 150、**先被 2011 拦下**，
   * 根本走不到 2013 —— 断言失败才发现。少一次这种调用就少一个能填错的地方。
   */
  @Test
  @DisplayName("发布：TARGET 指标的目标值被清空 → 2013（真库口径；草稿允许清空、发布才拒）")
  void publishRejectsTargetClearedOnRealData() {
    long versionId = templateService.ensureEditable(publishedDefaultVersionId()).versionId();
    long indicatorId = indicatorIdOf(versionId, "OTD_RATE");

    jdbcTemplate.update("UPDATE indicator SET target_value = NULL WHERE id = ?", indicatorId);

    assertThatThrownBy(() -> templateService.publish(versionId, requireVersion(versionId).getVersion()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("OTD_RATE")
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_TARGET_INVALID);
  }

  @Test
  @DisplayName("发布：TARGET 指标的目标值为 0 → 2013（SQL 判的是 <= 0，不是 IS NULL）")
  void publishRejectsZeroTargetOnRealData() {
    long versionId = templateService.ensureEditable(publishedDefaultVersionId()).versionId();
    long indicatorId = indicatorIdOf(versionId, "OTD_RATE");

    jdbcTemplate.update("UPDATE indicator SET target_value = 0 WHERE id = ?", indicatorId);

    assertThatThrownBy(() -> templateService.publish(versionId, requireVersion(versionId).getVersion()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("OTD_RATE")
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_TARGET_INVALID);
  }

  @Test
  @DisplayName("边界：非 TARGET 指标的目标值为 0 不影响发布——判据是「TARGET 且目标值不合法」")
  void nonTargetIndicatorWithZeroTargetIsAccepted() {
    long versionId = templateService.ensureEditable(publishedDefaultVersionId()).versionId();
    long indicatorId = indicatorIdOf(versionId, "IQC_PASS_RATE");

    // RATIO 指标留一个 0 目标值：它不参与目标达成率换算，不该拦住发布。
    // 若 SQL 写成「任何 target_value <= 0 都拒」，这条会红——发布会被 2013 拒掉。
    jdbcTemplate.update("UPDATE indicator SET target_value = 0 WHERE id = ?", indicatorId);

    // 默认模板其余条件本来就合规（权重是配平的），所以这里应当**发布成功**
    templateService.publish(versionId, requireVersion(versionId).getVersion());

    assertThat(requireVersion(versionId).getStatus()).isEqualTo(TemplateVersionStatus.PUBLISHED);
  }

  private long indicatorIdOf(long versionId, String code) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM indicator WHERE template_version_id = ? AND code = ?",
            Long.class,
            versionId,
            code);
    return id == null ? 0L : id;
  }


  @Test
  @DisplayName("契约：is_key 真的能从库里映射出来——只用全 0 的数据断言 isKey()==false 是恒真的假测试")
  void isKeyReallyMapsFromDatabase() {
    long versionId = templateService.ensureEditable(publishedDefaultVersionId()).versionId();
    Dimension dimension = snapshotDimensions(versionId).get(0);
    jdbcTemplate.update("UPDATE dimension SET is_key = 1 WHERE id = ?", dimension.getId());

    TemplateVersionTree tree = templateService.detail(versionId);

    // 实体是 `Boolean isKey`（Lombok 生成 getIsKey），MyBatis 由 `is_key` → `isKey` 自动映射。
    // 若把它写成 primitive `boolean isKey`，Lombok 会生成 `isKey()`、属性名被推断成 `key`，
    // 该列会被**静默丢弃**——默认的 autoMappingUnknownColumnBehavior 不报错。这条用例就是那个开关。
    assertThat(tree.dimensions().get(0).isKey()).isTrue();
  }

  @Test
  @DisplayName("对【已发布】版本的维度加指标 → 落到新草稿，且 dimension_id 指向**新草稿**的行（04-057 的回归）")
  void addIndicatorOnPublishedDimensionLandsInTheNewDraft() {
    long publishedVersionId = publishedDefaultVersionId();
    Dimension target = snapshotDimensions(publishedVersionId).get(0);
    String code = "NEW_CODE_" + System.nanoTime();

    EffectiveVersion effective =
        templateService.addIndicator(
            target.getId(), "新增指标", code, new BigDecimal("10.00"),
            IndicatorType.QUANT, ScoreMode.RATIO, "%", null, null, 99);

    assertThat(effective.copied()).isTrue();
    long draftId = effective.versionId();
    long expectedDimensionId = effective.requireDimensionId(target.getId(), publishedVersionId);

    // 关键断言：新指标必须挂在新草稿的对应维度上。若漏了 id 翻译，这里会是**旧版本**的维度 id ——
    // 插入照样成功（表上无外键），但从此看不进树、不计入权重、删维度也删不掉。
    Long actualDimensionId =
        jdbcTemplate.queryForObject(
            "SELECT dimension_id FROM indicator WHERE template_version_id = ? AND code = ? ORDER BY id DESC LIMIT 1",
            Long.class,
            draftId,
            code);
    assertThat(actualDimensionId).isEqualTo(expectedDimensionId);

    // 它必须在树里看得见（曾经是"看不见"的那类静默失败）
    TemplateVersionTree tree = templateService.detail(draftId);
    assertThat(
            tree.dimensions().stream()
                .flatMap(d -> d.indicators().stream())
                .map(IndicatorView::code))
        .contains(code);

    // 且它必须计入发布校验的权重统计（否则会报一个莫名其妙的"合计不是 100"）
    Long indicatorCountOnTarget =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM indicator WHERE template_version_id = ? AND dimension_id = ?",
            Long.class,
            draftId,
            expectedDimensionId);
    Long expectedCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) + 1 FROM indicator WHERE template_version_id = ? AND dimension_id = ?",
            Long.class,
            publishedVersionId,
            target.getId());
    assertThat(indicatorCountOnTarget).isEqualTo(expectedCount);
  }

  @Test
  @DisplayName("发布：对已发布版本再发布一次 → 3001（守卫 SQL 真的返回 0 行，不只是代码里有判断）")
  void publishOfAlreadyPublishedVersionConflicts() {
    long draftId = templateService.ensureEditable(publishedDefaultVersionId()).versionId();

    // 第一次发布：复制出来的草稿继承完整树，权重合计仍是 100，所以能过校验
    templateService.publish(draftId, requireVersion(draftId).getVersion());
    assertThat(versionStatus(draftId)).isEqualTo(TemplateVersionStatus.PUBLISHED);

    // 第二次：**用发布后的新 version**（发布时 version+1），于是 version 匹配、只有 status 不匹配——
    // 这样测的才是守卫里的 `status = 'DRAFT'` 那一段，而不是 version 不匹配。
    // 这条 SQL 的语义只有真实库能证明：mock 版 publishConflictOnGuardMiss 只能证明「代码里有这个判断」。
    int currentVersion = requireVersion(draftId).getVersion();
    assertThatThrownBy(() -> templateService.publish(draftId, currentVersion))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
  }

  // ---------------------------------------------------------------- 辅助

  private long publishedDefaultVersionId() {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM template_version WHERE version_no = 1 AND status = 'PUBLISHED' LIMIT 1",
            Long.class);
    assertThat(id).as("V8 应预置一个已发布版本").isNotNull();
    return id;
  }

  private List<Dimension> snapshotDimensions(long versionId) {
    return jdbcTemplate.query(
        "SELECT id, template_version_id, name, weight, sort_no, suggested_dept, is_key, version"
            + " FROM dimension WHERE template_version_id = ? ORDER BY sort_no",
        (rs, rowNum) -> {
          Dimension d = new Dimension();
          d.setId(rs.getLong("id"));
          d.setTemplateVersionId(rs.getLong("template_version_id"));
          d.setName(rs.getString("name"));
          d.setWeight(rs.getBigDecimal("weight"));
          d.setSortNo(rs.getInt("sort_no"));
          d.setSuggestedDept(rs.getString("suggested_dept"));
          d.setIsKey(rs.getBoolean("is_key"));
          d.setVersion(rs.getInt("version"));
          return d;
        },
        versionId);
  }

  private TemplateVersion requireVersion(long versionId) {
    return jdbcTemplate.queryForObject(
        "SELECT id, template_id, version_no, status, copied_from_version_id, published_at, version"
            + " FROM template_version WHERE id = ?",
        (rs, rowNum) -> {
          TemplateVersion v = new TemplateVersion();
          v.setId(rs.getLong("id"));
          v.setVersionNo(rs.getInt("version_no"));
          v.setStatus(TemplateVersionStatus.valueOf(rs.getString("status")));
          v.setVersion(rs.getInt("version"));
          return v;
        },
        versionId);
  }

  private TemplateVersionStatus versionStatus(long versionId) {
    return requireVersion(versionId).getStatus();
  }

  private Long copiedFrom(long versionId) {
    return jdbcTemplate.queryForObject(
        "SELECT copied_from_version_id FROM template_version WHERE id = ?", Long.class, versionId);
  }

  private long countIndicators(long dimensionId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM indicator WHERE dimension_id = ?", Long.class, dimensionId);
    return count == null ? 0 : count;
  }
}
