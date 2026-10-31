package com.spe.context.template.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import com.spe.context.template.api.TemplateVersionStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link TemplateService} 的单元测试（12 §3-W2 的"质量与测试"）。
 *
 * <p>重点盯两处最容易写错的地方：
 * <ol>
 *   <li><b>发布校验的口径与边界</b>——"维度合计"是模板内、`indicator.weight` 是**维度内**，两者都要精确
 *       等于 100，且 99.99 与 100.01 都要被拒（不能只判小于）；</li>
 *   <li><b>复制的两个后果</b>——复制出来的行是全新的（守卫必须用 0 而不是调用方那个属于旧行的版本号），
 *       而"复用别人已复制好的草稿"没有 id 映射、改删要报不存在。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

  private static final long TEMPLATE_ID = 5L;
  private static final long PUBLISHED_VERSION_ID = 2L;

  @Mock private TemplateMapper templateMapper;
  @Mock private TemplateVersionMapper templateVersionMapper;
  @Mock private DimensionMapper dimensionMapper;
  @Mock private IndicatorMapper indicatorMapper;
  @Mock private AuditFacade auditFacade;

  private TemplateService service;

  @BeforeEach
  void setUp() {
    service = new TemplateService(
        templateMapper, templateVersionMapper, dimensionMapper, indicatorMapper, auditFacade);
  }

  // ---------------------------------------------------------------- 发布校验

  @Test
  @DisplayName("发布：维度权重合计 99.98 → 2011，且报文里带实际值（用户要知道差多少）")
  void publishRejectsDimensionWeightBelowFull() {
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    when(dimensionMapper.findForValidation(1L))
        .thenReturn(List.of(validationRow(10L, "质量", "99.98", 4, "100.00")));

    assertThatThrownBy(() -> service.publish(1L, 0))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("99.98")
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_WEIGHT_INVALID);
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("发布：合计 100.01 同样被拒——边界两侧都要拦，不能只判小于")
  void publishRejectsDimensionWeightAboveFull() {
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    when(dimensionMapper.findForValidation(1L))
        .thenReturn(List.of(validationRow(10L, "质量", "100.01", 4, "100.00")));

    assertThatThrownBy(() -> service.publish(1L, 0))
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_WEIGHT_INVALID);
  }

  @Test
  @DisplayName("发布：某维度一个指标都没有 → 2012（LEFT JOIN 必须把空维度带回来）")
  void publishRejectsEmptyDimension() {
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    when(dimensionMapper.findForValidation(1L))
        .thenReturn(List.of(validationRow(10L, "质量", "100.00", 0, "0")));

    assertThatThrownBy(() -> service.publish(1L, 0))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("质量")
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_DIMENSION_EMPTY);
  }

  @Test
  @DisplayName("发布：某维度内指标合计 99.99 → 2011（这是「维度内」口径，不是模板内）")
  void publishRejectsIndicatorWeightNotFull() {
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    when(dimensionMapper.findForValidation(1L))
        .thenReturn(List.of(validationRow(10L, "质量", "100.00", 4, "99.99")));

    assertThatThrownBy(() -> service.publish(1L, 0))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("质量")
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_WEIGHT_INVALID);
  }

  @Test
  @DisplayName("发布：TARGET 目标值缺失或 ≤ 0 → 2013，并列出违规编码")
  void publishRejectsInvalidTarget() {
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    when(dimensionMapper.findForValidation(1L))
        .thenReturn(List.of(validationRow(10L, "交付", "100.00", 3, "100.00")));
    when(indicatorMapper.findInvalidTargets(1L)).thenReturn(List.of("OTD_RATE"));

    assertThatThrownBy(() -> service.publish(1L, 0))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("OTD_RATE")
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_TARGET_INVALID);
  }

  @Test
  @DisplayName("发布：版本内编码重复 → 2014（唯一键之外的发布期兜底）")
  void publishRejectsDuplicateCode() {
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    when(dimensionMapper.findForValidation(1L))
        .thenReturn(List.of(validationRow(10L, "质量", "100.00", 4, "100.00")));
    when(indicatorMapper.findDuplicateCodes(1L)).thenReturn(List.of("IQC_PASS_RATE"));

    assertThatThrownBy(() -> service.publish(1L, 0))
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE);
  }

  @Test
  @DisplayName("发布：模板一个维度都没有 → 2012")
  void publishRejectsNoDimension() {
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    when(dimensionMapper.findForValidation(1L)).thenReturn(List.of());

    assertThatThrownBy(() -> service.publish(1L, 0))
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_DIMENSION_EMPTY);
  }

  @Test
  @DisplayName("发布：守卫 UPDATE 影响 0 行（状态已被别人改走）→ 3001，且不写审计")
  void publishConflictOnGuardMiss() {
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    when(dimensionMapper.findForValidation(1L))
        .thenReturn(List.of(validationRow(10L, "质量", "100.00", 4, "100.00")));
    when(templateVersionMapper.publish(1L, 0)).thenReturn(0);

    assertThatThrownBy(() -> service.publish(1L, 0))
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("发布：校验通过 → 落状态并在成功之后留痕")
  void publishSuccessWritesAudit() {
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    when(dimensionMapper.findForValidation(1L))
        .thenReturn(List.of(validationRow(10L, "质量", "100.00", 4, "100.00")));
    when(templateVersionMapper.publish(1L, 0)).thenReturn(1);

    service.publish(1L, 0);

    verify(auditFacade).record(eq(AuditAction.TEMPLATE_VERSION_PUBLISH), any(), eq(1L), any());
  }

  // ---------------------------------------------------------------- ensureEditable

  @Test
  @DisplayName("ensureEditable：本来就是草稿 → 原样返回，不去锁模板行（不必为无复制付代价）")
  void ensureEditableKeepsDraft() {
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 7)));

    EffectiveVersion effective = service.ensureEditable(1L);

    assertThat(effective.versionId()).isEqualTo(1L);
    assertThat(effective.version()).isEqualTo(7);
    // 版本没换 ⇒ 行 id 就是它自己，不需要任何映射
    assertThat(effective.requireDimensionId(42L, 1L)).isEqualTo(42L);
    // 草稿路径**直接返回**：连"有没有同源草稿"都不必查（04-080 删掉模板行锁后，这条断言换成它）
    verify(templateVersionMapper, never()).findDraftCopiedFrom(anyLong(), anyLong());
  }

  @Test
  @DisplayName("ensureEditable：已发布 → 先查复用、再算 max 并复制整棵树，带回 id 映射并写 AUTO_COPY")
  void ensureEditableCopiesPublished() {
    stubInsertsWithGeneratedIds();
    when(templateVersionMapper.findByIdForUpdate(PUBLISHED_VERSION_ID))
        .thenReturn(Optional.of(published(PUBLISHED_VERSION_ID, TEMPLATE_ID, 3)));
    when(templateVersionMapper.findDraftCopiedFrom(TEMPLATE_ID, PUBLISHED_VERSION_ID))
        .thenReturn(Optional.empty());
    when(templateVersionMapper.findMaxVersionNoForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(3));
    when(dimensionMapper.findByVersionId(PUBLISHED_VERSION_ID))
        .thenReturn(List.of(dimension(10L, PUBLISHED_VERSION_ID)));
    when(indicatorMapper.findByVersionId(PUBLISHED_VERSION_ID))
        .thenReturn(List.of(indicator(100L, 10L)));

    EffectiveVersion effective = service.ensureEditable(PUBLISHED_VERSION_ID);

    assertThat(effective.versionId()).isNotEqualTo(PUBLISHED_VERSION_ID);
    assertThat(effective.requireDimensionId(10L, PUBLISHED_VERSION_ID)).isNotEqualTo(10L);
    assertThat(effective.requireIndicatorRow(100L, PUBLISHED_VERSION_ID, 0).id())
        .isNotEqualTo(100L);
    // 顺序不变式：**先查复用、再算 max**——已经有同源草稿就不该去动版本号（04-080 删掉模板行锁后，
    // 这里不再能断言"锁先于复用查询"，改成断言这条仍然成立的顺序）
    var order = org.mockito.Mockito.inOrder(templateVersionMapper);
    order.verify(templateVersionMapper).findDraftCopiedFrom(TEMPLATE_ID, PUBLISHED_VERSION_ID);
    order.verify(templateVersionMapper).findMaxVersionNoForUpdate(TEMPLATE_ID);
    // 源版本绝不能被发布/改动
    verify(templateVersionMapper, never()).publish(eq(PUBLISHED_VERSION_ID), anyInt());
    verify(auditFacade).record(eq(AuditAction.TEMPLATE_VERSION_AUTO_COPY), any(), any(), any());
  }

  @Test
  @DisplayName("ensureEditable：状态必须**当前读**——只凭普通读（快照）就断定'还是草稿'，会往已发布版本里写内容")
  void ensureEditableReadsStatusWithCurrentRead() {
    stubInsertsWithGeneratedIds();
    // 只提供"当前读"的返回。**故意不 stub 普通读** `findById`：早先的实现用它判状态，
    // 在这条用例里会拿到空、抛 2010 —— 也就是说这条用例在旧写法上是红的。
    when(templateVersionMapper.findByIdForUpdate(PUBLISHED_VERSION_ID))
        .thenReturn(Optional.of(published(PUBLISHED_VERSION_ID, TEMPLATE_ID, 3)));
    when(templateVersionMapper.findDraftCopiedFrom(TEMPLATE_ID, PUBLISHED_VERSION_ID))
        .thenReturn(Optional.empty());
    when(templateVersionMapper.findMaxVersionNoForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(3));
    when(dimensionMapper.findByVersionId(PUBLISHED_VERSION_ID))
        .thenReturn(List.of(dimension(10L, PUBLISHED_VERSION_ID)));
    when(indicatorMapper.findByVersionId(PUBLISHED_VERSION_ID))
        .thenReturn(List.of(indicator(100L, 10L)));

    EffectiveVersion effective = service.ensureEditable(PUBLISHED_VERSION_ID);

    // 已发布版本上的写操作必须经"复制"落地。若判状态用的是快照（旧写法），这里会走 sameAs 分支、
    // 把维度直接插进已发布版本，而版本守卫只能拦 UPDATE、拦不住 INSERT（05 ADR-3 就此失守）。
    assertThat(effective.versionId()).as("必须复制出新草稿，而不是把改动落进已发布版本")
        .isNotEqualTo(PUBLISHED_VERSION_ID);
  }

  @Test
  @DisplayName(
      "复用同源草稿：不写 AUTO_COPY；**配不上的那一侧**必须抛 2015——哪怕另一侧有映射、哪怕请求里的旧行 id 就在手里")
  void ensureEditableReusesExistingDraft() {
    when(templateVersionMapper.findByIdForUpdate(PUBLISHED_VERSION_ID))
        .thenReturn(Optional.of(published(PUBLISHED_VERSION_ID, TEMPLATE_ID, 3)));
    when(templateVersionMapper.findDraftCopiedFrom(TEMPLATE_ID, PUBLISHED_VERSION_ID))
        .thenReturn(Optional.of(draft(9L, 4, 0)));
    // ⚠️ 03 §31.7.12 的 P2-9：这两条当前读**显式 stub**——"草稿里没有维度行、但有指标行"是用例
    // 表达出来的前提，而不是 Mockito 对未 stub 方法返回空 List 的默认行为（后者让"删掉映射重建"的
    // 变异照样绿）。这里的形态正是 04-074 的 P0：**维度侧配不上、指标侧配得上**。
    when(dimensionMapper.findByVersionIdForShare(9L)).thenReturn(List.of());
    when(dimensionMapper.findByVersionIdForShare(PUBLISHED_VERSION_ID))
        .thenReturn(List.of(dimension(10L, PUBLISHED_VERSION_ID)));
    when(indicatorMapper.findByVersionIdForShare(9L)).thenReturn(List.of(indicator(200L, 55L)));
    when(indicatorMapper.findByVersionIdForShare(PUBLISHED_VERSION_ID))
        .thenReturn(List.of(indicator(100L, 10L)));

    EffectiveVersion effective = service.ensureEditable(PUBLISHED_VERSION_ID);

    assertThat(effective.versionId()).isEqualTo(9L);
    // 指标侧配上了：能翻译
    assertThat(effective.requireIndicatorRow(100L, PUBLISHED_VERSION_ID, 0).id()).isEqualTo(200L);
    // 维度侧配不上：必须**抛 2015**。修复前的写法会 (a) 因为指标映射非空而放行闸门、
    // 再 (b) 用 getOrDefault 把请求里的 10L 当答案返回 ⇒ 静默把改动写到旧行上。
    assertThatThrownBy(() -> effective.requireDimensionId(10L, PUBLISHED_VERSION_ID))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_DIMENSION_NOT_FOUND);
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("复制：连续两次复制拿到递增的版本号（2、3），且每次都用 max+1 而不是硬编码")
  void copyUsesIncreasingVersionNo() {
    stubInsertsWithGeneratedIds();
    when(templateVersionMapper.findByIdForUpdate(PUBLISHED_VERSION_ID))
        .thenReturn(Optional.of(published(PUBLISHED_VERSION_ID, TEMPLATE_ID, 3)));
    when(templateVersionMapper.findDraftCopiedFrom(eq(TEMPLATE_ID), anyLong()))
        .thenReturn(Optional.empty());
    when(templateVersionMapper.findMaxVersionNoForUpdate(TEMPLATE_ID))
        .thenReturn(Optional.of(1), Optional.of(2));
    // 源版本**有内容**：复制必须把维度与指标都带上。空树会让 dimension/indicator 的插入 stub 变成多余，
    // 也就测不到"整棵树被复制"这件事本身。
    when(dimensionMapper.findByVersionId(PUBLISHED_VERSION_ID))
        .thenReturn(List.of(dimension(10L, PUBLISHED_VERSION_ID)));
    when(indicatorMapper.findByVersionId(PUBLISHED_VERSION_ID))
        .thenReturn(List.of(indicator(100L, 10L)));

    service.ensureEditable(PUBLISHED_VERSION_ID);
    service.ensureEditable(PUBLISHED_VERSION_ID);

    ArgumentCaptor<TemplateVersion> inserted = ArgumentCaptor.forClass(TemplateVersion.class);
    verify(templateVersionMapper, times(2)).insert(inserted.capture());
    assertThat(inserted.getAllValues()).extracting(TemplateVersion::getVersionNo).containsExactly(2, 3);
    assertThat(inserted.getAllValues())
        .allSatisfy(
            v -> {
              assertThat(v.getStatus()).isEqualTo(TemplateVersionStatus.DRAFT);
              assertThat(v.getCopiedFromVersionId()).isEqualTo(PUBLISHED_VERSION_ID);
              assertThat(v.getVersion()).isZero();
            });
  }

  @Test
  @DisplayName("updateDimension：落到新草稿时守卫用 0（复制出来的行是全新的，不能拿旧行的版本号）")
  void updateDimensionUsesZeroGuardAfterCopy() {
    stubInsertsWithGeneratedIds();
    // ⚠️ 入口读是**当前读**：`TemplateService.requireDimension` 走的是 `findByIdForUpdate`（不是 `findById`）。
    // 这里一度还 stub 着旧的 `findById`，于是 mock 返回空 ⇒ 走不到被测分支，用例报出来的
    // 是"维度不存在"(2015) 而不是它真正想验的东西（5 条 mock 用例同时红）。
    when(dimensionMapper.findByIdForUpdate(10L))
        .thenReturn(Optional.of(dimension(10L, PUBLISHED_VERSION_ID)));
    when(templateVersionMapper.findByIdForUpdate(PUBLISHED_VERSION_ID))
        .thenReturn(Optional.of(published(PUBLISHED_VERSION_ID, TEMPLATE_ID, 3)));
    when(templateVersionMapper.findDraftCopiedFrom(TEMPLATE_ID, PUBLISHED_VERSION_ID))
        .thenReturn(Optional.empty());
    when(templateVersionMapper.findMaxVersionNoForUpdate(TEMPLATE_ID)).thenReturn(Optional.of(3));
    when(dimensionMapper.findByVersionId(PUBLISHED_VERSION_ID))
        .thenReturn(List.of(dimension(10L, PUBLISHED_VERSION_ID)));
    when(indicatorMapper.findByVersionId(PUBLISHED_VERSION_ID))
        .thenReturn(List.of(indicator(100L, 10L)));
    when(dimensionMapper.update(anyLong(), any(), any(), anyInt(), any(), eq(false), anyInt(), anyLong()))
        .thenReturn(1);

    service.updateDimension(10L, "质量", new BigDecimal("40.00"), 1, "质量", false, 99);

    // 关键：调用方传的是 99（旧行的版本号），实际守卫必须是 0；**且第二道闸必须钉在生效版本（新草稿）上**，
    // 而不是源版本——否则 04-074 那种"写到已发布版本"的错在 SQL 层就拦不住（0 行 → 3001 才是我们要的）。
    ArgumentCaptor<Long> versionIdCaptor = ArgumentCaptor.forClass(Long.class);
    verify(dimensionMapper)
        .update(
            anyLong(),
            eq("质量"),
            eq(new BigDecimal("40.00")),
            eq(1),
            eq("质量"),
            eq(false),
            eq(0),
            versionIdCaptor.capture());
    assertThat(versionIdCaptor.getValue())
        .as("第二道闸必须钉在生效版本上，不能是源版本")
        .isNotEqualTo(PUBLISHED_VERSION_ID);
  }

  @Test
  @DisplayName("updateDimension：复用别人的草稿时没有映射 → 2015，不去猜该改哪一行")
  void updateDimensionFailsWhenDraftReusedByOthers() {
    // ⚠️ 入口读是**当前读**：`TemplateService.requireDimension` 走的是 `findByIdForUpdate`（不是 `findById`）。
    // 这里一度还 stub 着旧的 `findById`，于是 mock 返回空 ⇒ 走不到被测分支，用例报出来的
    // 是"维度不存在"(2015) 而不是它真正想验的东西（5 条 mock 用例同时红）。
    when(dimensionMapper.findByIdForUpdate(10L))
        .thenReturn(Optional.of(dimension(10L, PUBLISHED_VERSION_ID)));
    when(templateVersionMapper.findByIdForUpdate(PUBLISHED_VERSION_ID))
        .thenReturn(Optional.of(published(PUBLISHED_VERSION_ID, TEMPLATE_ID, 3)));
    when(templateVersionMapper.findDraftCopiedFrom(TEMPLATE_ID, PUBLISHED_VERSION_ID))
        .thenReturn(Optional.of(draft(9L, 4, 0)));

    assertThatThrownBy(
            () -> service.updateDimension(10L, "质量", new BigDecimal("40.00"), 1, "质量", false, 0))
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_DIMENSION_NOT_FOUND);
    verify(dimensionMapper, never())
        .update(anyLong(), any(), any(), anyInt(), any(), eq(false), anyInt(), anyLong());
  }

  @Test
  @DisplayName("create：模板版本唯一键冲突翻成 3001，而不是漏成 5xxx（04-030 的教训）")
  void createTranslatesDuplicateKey() {
    when(templateVersionMapper.insert(any(TemplateVersion.class)))
        .thenThrow(new DuplicateKeyException("uk_template_version_no"));

    assertThatThrownBy(() -> service.create("季度绩效默认模板"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
  }

  @Test
  @DisplayName("addIndicator：保存时就查重（不等发布），编码重复 → 2014 且不插入")
  void addIndicatorChecksCodeEarly() {
    // 同上：入口读是当前读 `findByIdForUpdate`（误 stub 成 `findById` 时这两条 addIndicator 用例会报 2015）
    when(dimensionMapper.findByIdForUpdate(10L)).thenReturn(Optional.of(dimension(10L, 1L)));
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    when(indicatorMapper.countByCode(1L, "IQC_PASS_RATE", null)).thenReturn(1L);

    assertThatThrownBy(
            () ->
                service.addIndicator(
                    10L, "来料批次合格率", "IQC_PASS_RATE", new BigDecimal("30.00"),
                    null, null, "%", null, null, 1))
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE);
    verify(indicatorMapper, never()).insert(any(Indicator.class));
  }

  @Test
  @DisplayName("updateIndicator：把编码改成已存在的值 → 2014（唯一键也会被 UPDATE 踩到，不能漏成 5001）")
  void updateIndicatorTranslatesDuplicateCode() {
    // 同上：`TemplateService.requireIndicator` 也是当前读 `findByIdForUpdate`
    when(indicatorMapper.findByIdForUpdate(100L)).thenReturn(Optional.of(indicator(100L, 10L)));
    when(templateVersionMapper.findByIdForUpdate(PUBLISHED_VERSION_ID))
        .thenReturn(Optional.of(draft(PUBLISHED_VERSION_ID, 3, 0)));
    when(indicatorMapper.countByCode(PUBLISHED_VERSION_ID, "TAKEN_CODE", 100L)).thenReturn(0L);
    when(indicatorMapper.update(
            anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyLong()))
        .thenThrow(new DuplicateKeyException("uk_indicator_version_code"));

    assertThatThrownBy(
            () ->
                service.updateIndicator(
                    100L, "指标一", "TAKEN_CODE", new BigDecimal("100.00"), IndicatorType.QUANT,
                    ScoreMode.RATIO, "%", null, null, 1, 0))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE);
  }

  @Test
  @DisplayName("并发：锁等待超时（CannotAcquireLockException）也翻成 3001，不能漏成 5001（12 §E12 明确要求两类都翻）")
  void lockWaitTimeoutIsTranslated() {
    when(templateVersionMapper.insert(any(TemplateVersion.class)))
        .thenThrow(
            new org.springframework.dao.CannotAcquireLockException("Lock wait timeout exceeded"));

    assertThatThrownBy(() -> service.create("季度绩效默认模板"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
  }

  @Test
  @DisplayName("addIndicator：唯一键冲突 → 2014（前置查重说'没有'时，唯一键就是最后一道防线，不能漏成 5001）")
  void addIndicatorTranslatesUniqueKeyConflict() {
    // 同上：入口读是当前读 `findByIdForUpdate`（误 stub 成 `findById` 时这两条 addIndicator 用例会报 2015）
    when(dimensionMapper.findByIdForUpdate(10L)).thenReturn(Optional.of(dimension(10L, 1L)));
    when(templateVersionMapper.findByIdForUpdate(1L)).thenReturn(Optional.of(draft(1L, 1, 0)));
    // 关键：前置查重返回「没有这个编码」——并发下它读的是旧快照，正是 04-057 那个根因；
    // 此时真正的判定落在唯一键上，不翻译就会经兜底变成 5001（04-030 的形态）。
    when(indicatorMapper.countByCode(1L, "IQC_PASS_RATE", null)).thenReturn(0L);
    when(indicatorMapper.insert(any(Indicator.class)))
        .thenThrow(new DuplicateKeyException("uk_indicator_version_code"));

    assertThatThrownBy(
            () ->
                service.addIndicator(
                    10L, "来料批次合格率", "IQC_PASS_RATE", new BigDecimal("100.00"),
                    IndicatorType.QUANT, ScoreMode.RATIO, "%", null, null, 1))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE);
  }

  @Test
  @DisplayName("rename：改名字写 TEMPLATE_UPDATE 审计，detail 按 {before, after} 记真正变化的名字")
  void renameWritesAudit() {
    when(templateMapper.findById(5L)).thenReturn(Optional.of(template(5L, "旧名")));

    service.rename(5L, "新名");

    verify(templateMapper).updateName(5L, "新名");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditFacade)
        .record(
            eq(AuditAction.TEMPLATE_UPDATE),
            eq(AuditTargetType.TEMPLATE),
            eq(5L),
            detail.capture());
    assertThat(detail.getValue())
        .containsEntry("name", Map.of("before", "旧名", "after", "新名"));
  }

  @Test
  @DisplayName("rename：名字没变 → 既不写库也不写审计（只记真正变化，与 AuditDetails 同口径）")
  void renameWithoutChangeWritesNothing() {
    when(templateMapper.findById(5L)).thenReturn(Optional.of(template(5L, "同名")));

    service.rename(5L, "同名");

    verify(templateMapper, never()).updateName(anyLong(), any());
    verifyNoInteractions(auditFacade);
  }

  // ---------------------------------------------------------------- 辅助

  /**
   * 让 mock 的 `insert` 像 MyBatis 一样**回填自增主键**。
   *
   * <p>这不是测试的琐碎细节：`copyToNewDraft` 依赖"插入后 id 就位"来建立"旧行 → 新行"的映射，而这个
   * **隐式事实**在 mock 里不会自动发生（真实环境由 MyBatis-Plus 的 `IdType.AUTO` 兑现）。不模拟它，
   * 复制路径会以 `NullPointerException` 的形式失败——那是个假故障，但掩盖了"映射到底建没建对"这个问题。
   */
  private void stubInsertsWithGeneratedIds() {
    AtomicInteger versionId = new AtomicInteger(11);
    AtomicInteger dimensionId = new AtomicInteger(21);
    AtomicInteger indicatorId = new AtomicInteger(31);
    doAnswer(
            inv -> {
              ((TemplateVersion) inv.getArgument(0)).setId((long) versionId.getAndIncrement());
              return 1;
            })
        .when(templateVersionMapper)
        .insert(any(TemplateVersion.class));
    doAnswer(
            inv -> {
              ((Dimension) inv.getArgument(0)).setId((long) dimensionId.getAndIncrement());
              return 1;
            })
        .when(dimensionMapper)
        .insert(any(Dimension.class));
    doAnswer(
            inv -> {
              ((Indicator) inv.getArgument(0)).setId((long) indicatorId.getAndIncrement());
              return 1;
            })
        .when(indicatorMapper)
        .insert(any(Indicator.class));
  }

  private static Template template(long id, String name) {
    Template t = new Template();
    t.setId(id);
    t.setName(name);
    return t;
  }

  private static TemplateVersion draft(long id, int versionNo, int version) {
    TemplateVersion v = new TemplateVersion();
    v.setId(id);
    v.setTemplateId(TEMPLATE_ID);
    v.setVersionNo(versionNo);
    v.setStatus(TemplateVersionStatus.DRAFT);
    v.setVersion(version);
    return v;
  }

  private static TemplateVersion published(long id, long templateId, int versionNo) {
    TemplateVersion v = new TemplateVersion();
    v.setId(id);
    v.setTemplateId(templateId);
    v.setVersionNo(versionNo);
    v.setStatus(TemplateVersionStatus.PUBLISHED);
    v.setVersion(1);
    return v;
  }

  private static Dimension dimension(long id, long versionId) {
    Dimension d = new Dimension();
    d.setId(id);
    d.setTemplateVersionId(versionId);
    d.setName("质量");
    d.setWeight(new BigDecimal("100.00"));
    d.setSortNo(1);
    d.setIsKey(false);
    d.setVersion(0);
    return d;
  }

  private static Indicator indicator(long id, long dimensionId) {
    Indicator i = new Indicator();
    i.setId(id);
    i.setDimensionId(dimensionId);
    i.setTemplateVersionId(PUBLISHED_VERSION_ID);
    i.setCode("IQC_PASS_RATE");
    i.setWeight(new BigDecimal("100.00"));
    i.setSortNo(1);
    i.setVersion(0);
    return i;
  }

  private static DimensionValidationRow validationRow(
      long id, String name, String dimensionWeight, long indicatorCount, String indicatorSum) {
    DimensionValidationRow row = new DimensionValidationRow();
    row.setId(id);
    row.setName(name);
    row.setDimensionWeight(new BigDecimal(dimensionWeight));
    row.setIndicatorCount(indicatorCount);
    row.setIndicatorWeightSum(new BigDecimal(indicatorSum));
    return row;
  }
}
