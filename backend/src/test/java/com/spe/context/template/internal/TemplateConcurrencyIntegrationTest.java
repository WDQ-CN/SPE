package com.spe.context.template.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.template.api.IndicatorType;
import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * 并发用例（12 §3-W2 要求的三条）。**刻意不标 `@Transactional`**：其他线程看不到未提交的数据，
 * 外层事务会让这些用例变成假的。每个用例自己新建模板，不依赖共享数据，也不受彼此残留影响。
 *
 * <p>这些用例盯的是三件"错了也不会报错"的事：行级乐观锁的粒度、并发复制会不会产生平行草稿、
 * 以及唯一键冲突有没有被翻译成 3001 而不是漏成 5xxx。
 */
class TemplateConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TemplateService templateService;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  @DisplayName("并发：同一草稿下改两个**不同**维度 → 都成功（守卫粒度是行，不是版本）")
  void concurrentUpdatesOnDifferentRowsBothSucceed() throws Exception {
    long versionId = newDraftWithTwoDimensions();
    List<Dimension> dimensions = dimensionsOf(versionId);

    List<Object> results =
        runConcurrently(
            2,
            List.of(
                () -> updateDimensionName(dimensions.get(0), "质量-改"),
                () -> updateDimensionName(dimensions.get(1), "交付-改")));

    assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(EffectiveVersion.class));
    assertThat(dimensionsOf(versionId))
        .extracting(Dimension::getName)
        .containsExactlyInAnyOrder("质量-改", "交付-改");
  }

  @Test
  @DisplayName("并发：**同一个**维度被两个线程同时改 → 一个成功、另一个 3001（乐观锁拦住后写的那个）")
  void concurrentUpdatesOnSameRowOneWins() throws Exception {
    long versionId = newDraftWithTwoDimensions();
    Dimension target = dimensionsOf(versionId).get(0);

    List<Object> results =
        runConcurrently(
            2,
            List.of(
                () -> updateDimensionName(target, "A 改的"),
                () -> updateDimensionName(target, "B 改的")));

    List<Object> succeeded = results.stream().filter(r -> r instanceof EffectiveVersion).toList();
    List<Object> conflicted =
        results.stream()
            .filter(r -> r instanceof BizException e && e.errorCode() == ErrorCode.CONCURRENT_CONFLICT)
            .toList();
    assertThat(succeeded).hasSize(1);
    assertThat(conflicted).hasSize(1);
  }

  @Test
  @DisplayName("并发复制：两个线程同时改同一**已发布**版本 → 只产生一份草稿，不该出现平行草稿")
  void concurrentCopyProducesSingleDraft() throws Exception {
    long publishedVersionId = newPublishedTemplate();

    List<Object> results =
        runConcurrently(
            2,
            List.of(
                () -> templateService.ensureEditable(publishedVersionId),
                () -> templateService.ensureEditable(publishedVersionId)));

    assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(EffectiveVersion.class));
    Set<Long> effectiveVersionIds =
        results.stream().map(r -> ((EffectiveVersion) r).versionId()).collect(Collectors.toSet());
    // 两个线程必须落在同一个版本上：要么一个复制、另一个复用，绝不能各复制一份
    assertThat(effectiveVersionIds).hasSize(1);
    long draftCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM template_version WHERE copied_from_version_id = ? AND status = 'DRAFT'",
            Long.class,
            publishedVersionId);
    assertThat(draftCount).as("同一已发布版本只应有一份草稿").isEqualTo(1L);
  }

  @Test
  @DisplayName("并发：两个线程写同一个编码 → 恰好一个成功、另一个 2014（两道防线都不许漏成 5xxx）")
  void concurrentSameCodeIsRejectedWith2014() throws Exception {
    long versionId = newDraftWithTwoDimensions();
    Dimension dimension = dimensionsOf(versionId).get(0);

    List<Object> results =
        runConcurrently(
            2,
            List.of(
                () -> addIndicatorWithCode(dimension.getId(), "指标甲", "SAME_CODE"),
                () -> addIndicatorWithCode(dimension.getId(), "指标乙", "SAME_CODE")));

    long succeeded = results.stream().filter(r -> r instanceof EffectiveVersion).count();
    List<Object> rejected =
        results.stream()
            .filter(
                r ->
                    r instanceof BizException e
                        && e.errorCode() == ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE)
            .toList();

    // ⚠️ 这条用例**证明不了**「唯一键冲突被翻译」——2014 可能来自前置查重（抢到锁的那个先提交，
    // 另一个的查重就读到了它）。它证明的是另一件事：**并发下不会漏出 5xxx，也不会两条都成功**。
    // 「唯一键翻译」这条路径由 TemplateServiceTest.addIndicatorTranslatesUniqueKeyConflict 用 mock 钉住
    // （那里让 countByCode 返回 0、insert 抛 DuplicateKeyException），两者分工不同，不能互相替代。
    assertThat(succeeded).as("同一个编码只应有一个成功").isEqualTo(1L);
    assertThat(rejected).as("另一个必须是 2014，而不是 5xxx").hasSize(1);
  }

  private EffectiveVersion addIndicatorWithCode(long dimensionId, String name, String code) {
    return templateService.addIndicator(
        dimensionId, name, code, new BigDecimal("50.00"), IndicatorType.QUANT,
        com.spe.context.template.api.ScoreMode.RATIO, "%", null, null, 1);
  }

  @Test
  @DisplayName("并发：别的事务改了权重还没提交时，发布必须看得见它并拒绝——若发布成功，那份内容从未通过校验（04-057）")
  void publishSeesAnotherTransactionsUncommittedContentEdits() throws Exception {
    long versionId = newPublishableDraft();
    Dimension dimension = dimensionsOf(versionId).get(0);
    TemplateVersion draft = versionRow(versionId);

    // 主线程起一笔事务：把维度权重 100 → 60（合计不再合规），**先不提交**。
    // 此刻它持有版本行的锁（updateDimension → ensureEditable 的 FOR UPDATE）。
    TransactionStatus editing = txManager.getTransaction(new DefaultTransactionDefinition());
    templateService.updateDimension(
        dimension.getId(), dimension.getName(), new BigDecimal("60.00"), dimension.getSortNo(),
        dimension.getSuggestedDept(), Boolean.TRUE.equals(dimension.getIsKey()), dimension.getVersion());

    // 另一个线程去发布：它第一条就是 findByIdForUpdate，会被上面的锁挡住
    CountDownLatch started = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<Object> publishing =
        pool.submit(
            () -> {
              started.countDown();
              try {
                templateService.publish(versionId, draft.getVersion());
                return "published";
              } catch (Exception ex) {
                return ex;
              }
            });
    started.await();
    Thread.sleep(500); // 时序手段：够它发起那条锁定读即可（本用例断言的是结果，不是时序）

    txManager.commit(editing);

    Object result = publishing.get(60, TimeUnit.SECONDS);
    pool.shutdownNow();

    // 这条断言同时钉住两件事：① 发布的校验读集能看到别人刚提交的内容改动（即 read view 是在锁定读
    // **之后**才建立的）；② 不合规的内容不会被发布出去。若把 publish 的第一条从 findByIdForUpdate
    // 换回 requireVersion（普通读），这里会拿到 "published"，而库里会留下一个合计 60 的已发布版本。
    assertThat(result)
        .as("发布必须拒绝；拿到 published 说明校验读的是旧快照")
        .isInstanceOf(BizException.class);
    assertThat(((BizException) result).errorCode()).isEqualTo(ErrorCode.TEMPLATE_WEIGHT_INVALID);
    assertThat(versionRow(versionId).getStatus())
        .as("校验没过就不能落 PUBLISHED")
        .isEqualTo(com.spe.context.template.api.TemplateVersionStatus.DRAFT);
  }

  // ---------------------------------------------------------------- 12 §E5 的并发意图（P2 深审加）

  @Test
  @DisplayName("并发：已发布版本上改两个**不同**维度 → 都应落在同一份新草稿（12 §E5 的明文意图）")
  void concurrentEditsOnPublishedVersionShareOneDraft() throws Exception {
    long publishedVersionId = newPublishedTemplateWithTwoDimensions();
    List<Dimension> dimensions = dimensionsOf(publishedVersionId);
    assertThat(dimensions).hasSize(2);

    List<Object> results =
        runConcurrently(
            2,
            List.of(
                () -> updateDimensionName(dimensions.get(0), "质量-并发改"),
                () -> updateDimensionName(dimensions.get(1), "交付-并发改")));

    // 12 §E5 原文：「两个人同时改同一已发布版本的**不同维度**，理应落在**同一份**新草稿上」
    assertThat(results)
        .as("两个请求都应成功——第二个应当复用同一份草稿，而不是报 2015")
        .allSatisfy(r -> assertThat(r).isInstanceOf(EffectiveVersion.class));

    Set<Long> effectiveVersionIds =
        results.stream().map(r -> ((EffectiveVersion) r).versionId()).collect(Collectors.toSet());
    assertThat(effectiveVersionIds).as("两个改动必须落在同一个草稿上").hasSize(1);

    long draftId = effectiveVersionIds.iterator().next();
    assertThat(draftId).isNotEqualTo(publishedVersionId);
    assertThat(versionRow(draftId).getStatus())
        .isEqualTo(com.spe.context.template.api.TemplateVersionStatus.DRAFT);
    // 源已发布版本一个字段都没动
    assertThat(dimensionsOf(publishedVersionId))
        .extracting(Dimension::getName)
        .containsExactly("质量", "交付");
    // 新草稿里两处改动都在
    assertThat(dimensionsOf(draftId))
        .extracting(Dimension::getName)
        .containsExactlyInAnyOrder("质量-并发改", "交付-并发改");
  }

  // ---------------------------------------------------------------- 辅助

  /** 新建并**发布**一个带两个维度的模板（各 50 权重、各 1 个指标 100），供 §E5 的并发用例使用。 */
  private long newPublishedTemplateWithTwoDimensions() {
    TemplateSummary created = templateService.create("并发双维度用例-" + System.nanoTime());
    long versionId = created.latestVersionId();
    versionId =
        templateService.addDimension(versionId, "质量", new BigDecimal("50.00"), 1, "质量", false).versionId();
    versionId =
        templateService
            .addDimension(versionId, "交付", new BigDecimal("50.00"), 2, "交付物流", false)
            .versionId();

    for (Dimension dimension : dimensionsOf(versionId)) {
      templateService.addIndicator(
          dimension.getId(),
          dimension.getName() + "指标",
          "CONC_" + dimension.getSortNo() + "_" + System.nanoTime(),
          new BigDecimal("100.00"),
          IndicatorType.QUANT,
          com.spe.context.template.api.ScoreMode.RATIO,
          "%",
          null,
          null,
          1);
    }
    TemplateVersion draft = versionRow(versionId);
    templateService.publish(versionId, draft.getVersion());
    return versionId;
  }

  /** 建一个「内容合规但未发布」的草稿：1 个维度(100) + 1 个指标(100)。 */
  private long newPublishableDraft() {
    TemplateSummary created = templateService.create("发布并发用例-" + System.nanoTime());
    long versionId = created.latestVersionId();
    EffectiveVersion afterDimension =
        templateService.addDimension(versionId, "质量", new BigDecimal("100.00"), 1, "质量", false);
    long effectiveVersionId = afterDimension.versionId();
    Dimension dimension = dimensionsOf(effectiveVersionId).get(0);
    templateService.addIndicator(
        dimension.getId(), "来料批次合格率", "IQC_" + System.nanoTime(), new BigDecimal("100.00"),
        IndicatorType.QUANT, com.spe.context.template.api.ScoreMode.RATIO, "%", null, null, 1);
    return effectiveVersionId;
  }

  /** 新建一个只有草稿的模板，带两个各 100 权重的维度（够并发用例用，不必发布）。 */
  private long newDraftWithTwoDimensions() {
    TemplateSummary created = templateService.create("并发用例模板-" + System.nanoTime());
    long versionId = created.latestVersionId();
    templateService.addDimension(versionId, "质量", new BigDecimal("50.00"), 1, "质量", false);
    templateService.addDimension(versionId, "交付", new BigDecimal("50.00"), 2, "交付物流", false);
    return versionId;
  }

  /** 新建并**发布**一个模板（发布校验要求维度合计与维度内指标合计都是 100）。 */
  private long newPublishedTemplate() {
    TemplateSummary created = templateService.create("并发发布用例-" + System.nanoTime());
    long versionId = created.latestVersionId();
    EffectiveVersion afterDimension =
        templateService.addDimension(versionId, "质量", new BigDecimal("100.00"), 1, "质量", false);
    long effectiveVersionId = afterDimension.versionId();
    Dimension dimension = dimensionsOf(effectiveVersionId).get(0);
    templateService.addIndicator(
        dimension.getId(), "来料批次合格率", "IQC_" + System.nanoTime(), new BigDecimal("100.00"),
        IndicatorType.QUANT, com.spe.context.template.api.ScoreMode.RATIO, "%", null, null, 1);
    TemplateVersion draft = versionRow(effectiveVersionId);
    templateService.publish(effectiveVersionId, draft.getVersion());
    return effectiveVersionId;
  }

  private EffectiveVersion updateDimensionName(Dimension dimension, String name) {
    return templateService.updateDimension(
        dimension.getId(), name, dimension.getWeight(), dimension.getSortNo(),
        dimension.getSuggestedDept(), Boolean.TRUE.equals(dimension.getIsKey()), dimension.getVersion());
  }

  private List<Dimension> dimensionsOf(long versionId) {
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

  private TemplateVersion versionRow(long versionId) {
    return jdbcTemplate.queryForObject(
        "SELECT id, version_no, status, version FROM template_version WHERE id = ?",
        (rs, rowNum) -> {
          TemplateVersion v = new TemplateVersion();
          v.setId(rs.getLong("id"));
          v.setVersionNo(rs.getInt("version_no"));
          v.setStatus(com.spe.context.template.api.TemplateVersionStatus.valueOf(rs.getString("status")));
          v.setVersion(rs.getInt("version"));
          return v;
        },
        versionId);
  }

  /**
   * 让若干任务**尽可能同时**开始，返回每个任务的结果或它抛出的异常。
   *
   * <p>`CountDownLatch` 用来对齐起点（否则线程池的调度误差会让"并发"退化成串行）；异常原样收集回来，
   * 由用例判断"谁成功、谁冲突"。
   */
  private List<Object> runConcurrently(int threads, List<Callable<Object>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (Callable<Object> task : tasks) {
      futures.add(
          pool.submit(
              () -> {
                start.await();
                try {
                  return task.call();
                } catch (Exception ex) {
                  return ex;
                }
              }));
    }
    start.countDown();
    List<Object> results = new ArrayList<>();
    for (Future<Object> future : futures) {
      results.add(future.get(60, TimeUnit.SECONDS));
    }
    pool.shutdownNow();
    return results;
  }
}
