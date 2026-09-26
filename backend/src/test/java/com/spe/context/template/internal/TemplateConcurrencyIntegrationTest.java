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

/**
 * 并发用例（12 §3-W2 要求的三条）。**刻意不标 `@Transactional`**：其他线程看不到未提交的数据，
 * 外层事务会让这些用例变成假的。每个用例自己新建模板，不依赖共享数据，也不受彼此残留影响。
 *
 * <p>这些用例盯的是三件"错了也不会报错"的事：行级乐观锁的粒度、并发复制会不会产生平行草稿、
 * 以及唯一键冲突有没有被翻译成 3001 而不是漏成 5xxx。
 */
class TemplateConcurrencyIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TemplateService templateService;

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
  @DisplayName("并发：版本内重复编码 → 2014（唯一键冲突必须被翻译，不能漏成 5xxx）")
  void duplicateIndicatorCodeIsTranslated() {
    long versionId = newDraftWithTwoDimensions();
    Dimension dimension = dimensionsOf(versionId).get(0);
    templateService.addIndicator(
        dimension.getId(), "指标一", "DUP_CODE", new BigDecimal("100.00"), IndicatorType.QUANT,
        com.spe.context.template.api.ScoreMode.DIRECT, "%", null, null, 1);

    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                templateService.addIndicator(
                    dimension.getId(), "指标二", "dup_code", new BigDecimal("10.00"),
                    IndicatorType.QUANT, com.spe.context.template.api.ScoreMode.DIRECT, "%", null, null, 2));

    // 大小写不同但库 collation 视为冲突（12 §E11 沿用 supplier.code 的口径）
    assertThat(thrown).isInstanceOf(BizException.class);
    assertThat(((BizException) thrown).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_INDICATOR_CODE_DUPLICATE);
  }

  // ---------------------------------------------------------------- 辅助

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
