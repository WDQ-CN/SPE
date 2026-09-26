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

    // 指标：13 个，编码与权重逐条列出（不是"取第一条看看"）
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

    assertThat(byCode.get("IQC_PASS_RATE").weight()).isEqualByComparingTo("30");
    assertThat(byCode.get("QUALITY_ACCIDENT").weight()).isEqualByComparingTo("20");
    assertThat(byCode.get("QMS_AUDIT_SCORE").weight()).isEqualByComparingTo("30");
    assertThat(byCode.get("OTD_RATE").weight()).isEqualByComparingTo("50");
    assertThat(byCode.get("ISSUE_RESPONSE").weight()).isEqualByComparingTo("40");

    // 计分方式：TARGET 的目标值按百分数数值记（与 01 §6.1 的公式同口径）
    assertThat(byCode.get("OTD_RATE").scoreMode()).isEqualTo(ScoreMode.TARGET);
    assertThat(byCode.get("OTD_RATE").targetValue()).isEqualByComparingTo("98");
    assertThat(byCode.get("COST_DOWN_RATE").targetValue()).isEqualByComparingTo("3");

    // 定性指标没有计分方式（策略由 type 决定）；定性档位文案只对 URGENT_RESPONSE 有（附录 A 只给了它）
    assertThat(byCode.get("URGENT_RESPONSE").type()).isEqualTo(IndicatorType.QUAL);
    assertThat(byCode.get("URGENT_RESPONSE").scoreMode()).isNull();
    assertThat(byCode.get("URGENT_RESPONSE").standard()).contains("4 小时内响应");
    assertThat(byCode.get("ISSUE_RESPONSE").standard()).isNull();

    // DIRECT 指标的换算表必须落进 standard，否则 P3 会把它当普通 0~100 直接打分（12 §E15 的 R-2）
    assertThat(byCode.get("QUALITY_ACCIDENT").standard()).isEqualTo("0 次=100，1 次=60，≥2 次=0");
    assertThat(byCode.get("QMS_AUDIT_SCORE").standard()).isEqualTo("0~100");
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
    assertThat(effective.resolveDimension(sourceDimensions.get(0).getId()))
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
