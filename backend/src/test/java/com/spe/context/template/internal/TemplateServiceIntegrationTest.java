package com.spe.context.template.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;

/**
 * template 上下文的集成测试（12 §3-W2）：跑在 Testcontainers 的真实 MySQL 上，迁移 V1~V8 已应用。
 *
 * <p>类级 `@Transactional` 让每个用例结束后回滚，不污染共享的容器库——但**并发用例不能这样做**
 * （其它线程看不到未提交的数据），所以它们单独放在 {@code TemplateConcurrencyIntegrationTest}。
 */
@Transactional
class TemplateServiceIntegrationTest extends AbstractIntegrationTest {

  /** `V8` 种下的默认模板名。**按名收敛查询范围**，不靠"全表扫描的行顺序"（03 §31.7.14 的 P2-10）。 */
  private static final String DEFAULT_TEMPLATE_NAME = "季度绩效默认模板";

  /** 用例自己加的那个空维度名：断言 2012 的报文里点名的是**它**（证明违规行真的从 SQL 里回来了）。 */
  private static final String EMPTY_DIMENSION_NAME = "空维度-无任何指标";

  /** `V9` 的回填脚本。**按类路径读文件本体**执行，不把那段 UPDATE 抄一份进测试——抄一份就守不住迁移。 */
  private static final String V9_MIGRATION_FILE = "db/migration/V9__backfill_qualitative_standard.sql";

  /** `V9` 回填的 5 个定性指标（脚本里 `WHERE i.code IN (...)` 的那 5 个，顺序与脚本无关）。 */
  private static final List<String> V9_BACKFILLED_CODES =
      List.of(
          "PRICE_COMPETITIVENESS", "PAYMENT_COOP", "ISSUE_RESPONSE", "ABNORMAL_COOP",
          "CONTACT_STABILITY");

  /** 模拟"用户自己写过的文案"，用来验 `V9` 的 `standard IS NULL` 守卫不会覆盖它。 */
  private static final String CUSTOM_STANDARD = "用户自己写的";

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

    // 定性指标没有计分方式（策略由 type 决定）。
    // ⚠️ 2026-09-26：附录 A 补齐了另外 5 个指标的档位文案、`V11` 回填入库 ⇒ 本条从
    // 「只有 URGENT_RESPONSE 有文案」改为「6 个定性指标全都有」。**是这条用例先变红**，
    // 才确认回填真的生效了（它原本钉的是"留空"这个已过时的前提）。
    assertThat(byCode.values().stream().filter(i -> i.type() == IndicatorType.QUAL))
        .hasSize(6)
        .allSatisfy(i -> assertThat(i.scoreMode()).isNull());
    assertThat(byCode.get("URGENT_RESPONSE").standard()).contains("4 小时内响应");
    // 逐字断言其中一条：这同时**证明库里的中文编码是对的**——只看 Maven 控制台会显示乱码，
    // 那是控制台代码页（观测端）的问题，不是存储端（CLAUDE「默认约定」第 4 条）。
    assertThat(byCode.get("ISSUE_RESPONSE").standard())
        .isEqualTo("5=≤2h 响应并给出可行方案；4=≤8h 响应，方案基本可行；3=≤24h 响应，方案需确认；"
            + "2=≤48h 响应，方案流于形式；1=>48h 未响应或需多次催办");
    // 另外 4 条按**结构不变式**断言（不把 5 段长文案抄两遍——内容改了不该让用例陪葬）：
    assertThat(
            List.of("PRICE_COMPETITIVENESS", "PAYMENT_COOP", "ABNORMAL_COOP", "CONTACT_STABILITY"))
        .allSatisfy(
            code ->
                assertThat(byCode.get(code).standard())
                    .as("指标 %s 的档位文案", code)
                    .isNotBlank()
                    .startsWith("5=")
                    .contains("；4=")
                    .contains("；3=")
                    .contains("；2=")
                    .contains("；1="));

    // ⚠️ 上面那条**结构不变式堵不住"张冠李戴"**（03 §31.7.15 指出的第 1 个洞）：两条文案互换后
    // 5 段结构一字不差，结构断言照样全绿。因此每个 code 再钉一个**只可能出现在它自己文案里**的关键词——
    // 既不复制 5 段长文案（内容微调不会让用例陪葬），又让"两个指标的文案写反了"必然变红。
    Map<String, String> distinctiveKeyword =
        Map.of(
            "PRICE_COMPETITIVENESS", "基准",
            "PAYMENT_COOP", "账期",
            "ISSUE_RESPONSE", "响应",
            "ABNORMAL_COOP", "整改",
            "CONTACT_STABILITY", "更换");
    assertThat(distinctiveKeyword).as("6 个定性指标里，这一张表要覆盖除 URGENT_RESPONSE 外的全部 5 个").hasSize(5);
    long matched =
        distinctiveKeyword.entrySet().stream()
            .filter(e -> byCode.get(e.getKey()).standard().contains(e.getValue()))
            .count();
    assertThat(matched)
        .as("5 条文案必须各自命中自己的区别性关键词（少一条就说明有两条被写反/写串了）")
        .isEqualTo(5L);
    distinctiveKeyword.forEach(
        (code, keyword) ->
            assertThat(byCode.get(code).standard())
                .as("指标 %s 的档位文案里必须出现【%s】", code, keyword)
                .contains(keyword));

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

    assertThat(draft.versionId()).isNotEqualTo(versionId);
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

    assertThat(effective.versionId()).isNotEqualTo(sourceVersionId);
    // 原版本仍是 PUBLISHED、维度数未变
    assertThat(versionStatus(sourceVersionId)).isEqualTo(TemplateVersionStatus.PUBLISHED);
    assertThat(snapshotDimensions(sourceVersionId)).hasSize(sourceDimensions.size());
    // 新草稿是 DRAFT，且记下了复制来源
    assertThat(versionStatus(effective.versionId())).isEqualTo(TemplateVersionStatus.DRAFT);
    assertThat(copiedFrom(effective.versionId())).isEqualTo(sourceVersionId);
    // 维度与指标都被复制，映射可用；且映射里带的是**新行 id + 新行当前 version（恒为 0）**
    assertThat(snapshotDimensions(effective.versionId())).hasSize(sourceDimensions.size());
    assertThat(effective.dimensionTargets()).hasSize(sourceDimensions.size());
    assertThat(effective.indicatorTargets()).hasSize(13);
    assertThat(effective.dimensionTargets().values()).allSatisfy(t -> assertThat(t.version()).isZero());
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
    long draftCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM template_version WHERE copied_from_version_id = ? AND status = 'DRAFT'",
            Long.class,
            sourceVersionId);
    assertThat(draftCount).isEqualTo(1L);
  }

  // ------------------------------------------------- P0 回归：映射不完整时**不得**写穿到已发布版本

  @Test
  @DisplayName(
      "P0 回归（04-074）+ D1 升级（04-081）：改过名的行用旧行 id 再写，现在能**精确定位到草稿副本**；"
          + "而无论走哪条路径，**已发布版本一行都不能变**")
  void staleRowIdWithPartialMapIsRejectedAndLeavesPublishedUntouched() {
    long publishedVersionId = publishedDefaultVersionId();
    Dimension target = snapshotDimensions(publishedVersionId).get(0);
    String publishedName = target.getName();
    int publishedRowVersion = target.getVersion();

    // ① 改一次已发布版本 → 自动复制出新草稿，改动落在**草稿副本**上
    EffectiveVersion first =
        templateService.updateDimension(
            target.getId(),
            "RENAME-1-" + System.nanoTime(),
            target.getWeight(),
            target.getSortNo(),
            target.getSuggestedDept(),
            Boolean.TRUE.equals(target.getIsKey()),
            publishedRowVersion);
    assertThat(first.versionId()).isNotEqualTo(publishedVersionId);

    // ② 此时草稿副本的 name 与源行**不同** ⇒ 再复用同一份草稿时按 (sort_no, name) 配对**配不上**，
    //    维度映射变成部分（空），而指标映射非空（指标的配对键 code 没变）——这正是 P0 的触发形态。
    //    请求方是"未刷新的页签 / 响应丢失后重试"：手持**已发布版本的旧行 id + 旧 version**。
    Throwable thrown =
        catchThrowable(
            () ->
                templateService.updateDimension(
                    target.getId(),
                    "RENAME-2-" + System.nanoTime(),
                    target.getWeight(),
                    target.getSortNo(),
                    target.getSuggestedDept(),
                    Boolean.TRUE.equals(target.getIsKey()),
                    publishedRowVersion));

    // ③ 先断言**损害为 0**：已发布版本一行都不能变。
    //    ⚠️ 这一步刻意放在"是否抛错"之前——修复前它会把真实被改成的值打出来，
    //    而只看"有没有抛异常"只能说明"没拒绝"，说不出被改成了什么（CLAUDE 默认约定第 5 条）。
    Dimension after =
        snapshotDimensions(publishedVersionId).stream()
            .filter(d -> d.getId() == target.getId())
            .findFirst()
            .orElseThrow();
    assertThat(after.getName())
        .as("已发布版本的维度名被就地改写了（P0：写穿到 PUBLISHED）")
        .isEqualTo(publishedName);
    assertThat(after.getVersion())
        .as("已发布版本的乐观锁列被推进了（P0：写穿到 PUBLISHED）")
        .isEqualTo(publishedRowVersion);

    // ④ 04-081 之后：身份由 `src_dimension_id` 提供 ⇒ 这次写**应当成功**（精确落在草稿副本上），
    //    不再报 2015。旧实现只能按 (sort_no, name) 配对，配不上就报 2015 —— 那是 P0 的**止损**，
    //    现在换成了根治。"配不上就 2015"仍然存在，只是触发条件收窄为"那一行确实不在生效版本里"
    //    （由 `TemplateServiceTest.ensureEditableReusesExistingDraft` 覆盖）。
    assertThat(thrown)
        .as("改过名的行应当靠源行 id 精确定位，而不是报 2015（04-081 的升级点）")
        .isNull();
  }

  @Test
  @DisplayName(
      "D1 回归（04-081）：草稿里改过维度名之后，复用仍能精确定位 —— 靠复制时留下的 src_dimension_id，"
          + "而不是可编辑的业务键（旧实现到这里只能报 2015）")
  void reusePairsBySourceRowIdEvenAfterRename() {
    long publishedVersionId = publishedDefaultVersionId();
    Dimension target = snapshotDimensions(publishedVersionId).get(0);

    // ① 改一次已发布版本 → 复制出新草稿；副本必须记下"我从哪一行复制来"
    EffectiveVersion first =
        templateService.updateDimension(
            target.getId(),
            "RENAME-D1-" + System.nanoTime(),
            target.getWeight(),
            target.getSortNo(),
            target.getSuggestedDept(),
            Boolean.TRUE.equals(target.getIsKey()),
            target.getVersion());
    assertThat(first.versionId()).isNotEqualTo(publishedVersionId);
    Long srcId =
        jdbcTemplate.queryForObject(
            "SELECT src_dimension_id FROM dimension WHERE template_version_id = ? ORDER BY id LIMIT 1",
            Long.class,
            first.versionId());
    assertThat(srcId).as("副本必须记下源行 id（否则复用只能靠业务键猜）").isEqualTo(target.getId());

    // ② 再用**已发布版本的旧行 id** 发一次写（走复用分支）。此刻业务键已经发散——草稿里那一行改了名——
    //    旧实现（按 (sort_no, name) 配对）只能报 2015；现在应当按 src_dimension_id 精确命中。
    EffectiveVersion reused =
        templateService.updateDimension(
            target.getId(),
            "RENAME-D1-2-" + System.nanoTime(),
            target.getWeight(),
            target.getSortNo(),
            target.getSuggestedDept(),
            Boolean.TRUE.equals(target.getIsKey()),
            target.getVersion());

    assertThat(reused.versionId()).as("第二次应当复用已存在的草稿").isEqualTo(first.versionId());
    // 与 P0 用例同一个不变式：已发布版本一行都不能变
    Dimension after =
        snapshotDimensions(publishedVersionId).stream()
            .filter(d -> d.getId() == target.getId())
            .findFirst()
            .orElseThrow();
    assertThat(after.getName()).as("已发布版本被就地改写了").isEqualTo(target.getName());
  }

  @Test
  @DisplayName(
      "P1 回归（04-074）：复用别人先建好的草稿时，守卫必须用**草稿那一行**的 version——"
          + "用调用方手里的源行版本号会把「发布前被编辑过的行」误报成 3001")
  void reusedDraftGuardUsesTargetRowVersion() {
    // ① 造一个"发布前被编辑过"的已发布版本：默认模板 → 草稿 → 改一个维度 → 发布
    long firstDraftId = templateService.ensureEditable(publishedDefaultVersionId()).versionId();
    Dimension draftFirst = snapshotDimensions(firstDraftId).get(0);
    templateService.updateDimension(
        draftFirst.getId(),
        draftFirst.getName(),
        draftFirst.getWeight(),
        draftFirst.getSortNo(),
        draftFirst.getSuggestedDept(),
        Boolean.TRUE.equals(draftFirst.getIsKey()),
        draftFirst.getVersion());
    templateService.publish(firstDraftId, requireVersion(firstDraftId).getVersion());

    Dimension publishedFirst = snapshotDimensions(firstDraftId).get(0);
    Dimension publishedSecond = snapshotDimensions(firstDraftId).get(1);
    assertThat(publishedFirst.getVersion())
        .as("fixture 自检：这一行在已发布版本里应当被编辑过（version=1，不是 0）")
        .isEqualTo(1);

    // ② 用**另一个**维度触发自动复制（copy 分支）——副本行的 version 全是 0
    EffectiveVersion copied =
        templateService.updateDimension(
            publishedSecond.getId(),
            "第二维度改名-" + System.nanoTime(),
            publishedSecond.getWeight(),
            publishedSecond.getSortNo(),
            publishedSecond.getSuggestedDept(),
            Boolean.TRUE.equals(publishedSecond.getIsKey()),
            publishedSecond.getVersion());
    assertThat(copied.versionId()).isNotEqualTo(firstDraftId);

    // ③ 再用第一个维度的**已发布行 id + 它在已发布版本里的 version(=1)** 发一次写：
    //    这次走**复用**分支，目标行是草稿里的副本（version=0）⇒ 守卫必须取 0。
    //    修复前取的是调用方传进来的 1 ⇒ UPDATE 影响 0 行 ⇒ 一个**没人动过**的行被误报 3001。
    EffectiveVersion reused =
        templateService.updateDimension(
            publishedFirst.getId(),
            "第一维度改名-" + System.nanoTime(),
            publishedFirst.getWeight(),
            publishedFirst.getSortNo(),
            publishedFirst.getSuggestedDept(),
            Boolean.TRUE.equals(publishedFirst.getIsKey()),
            publishedFirst.getVersion());

    assertThat(reused.versionId()).as("第二次应当复用已存在的草稿，而不是再复制一份")
        .isEqualTo(copied.versionId());
    assertThat(versionStatus(copied.versionId())).isEqualTo(TemplateVersionStatus.DRAFT);
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

  // ---------------------------------------------------------------- 2012 的 SQL 判定（03 §31.7.13 补）

  /**
   * 03 §31.7.13：2012（某维度一个指标都没有）原先**只有一条 mock 掉 `findForValidation` 的单测**
   * （`TemplateServiceTest.publishRejectsEmptyDimension` 直接 stub 出 `indicatorCount = 0` 的那一行），
   * 而"空维度会不会出现在校验结果里"这件事写在 SQL 的 `LEFT JOIN` 上——把 `LEFT JOIN` 换成 `INNER JOIN`，
   * 那条单测**照样全绿**，而真库里违规的维度会从结果里凭空消失、**发布反而通过**。这条在真库上补上。
   */
  @Test
  @DisplayName("发布：草稿里加一个**没有指标**的维度 → 2012（真库口径；SQL 换 INNER JOIN 时必须变红）")
  void publishRejectsDimensionWithoutAnyIndicatorOnRealData() {
    long versionId = templateService.ensureEditable(publishedDefaultVersionId()).versionId();

    // 只加一个**权重 0、没有指标**的维度：维度合计仍是 100 ⇒ 先走的 2011 不会命中，
    // 命中的必然是"每维度至少 1 个指标"那条 2012（否则这条用例证明不了 LEFT JOIN 的事）。
    templateService.addDimension(versionId, EMPTY_DIMENSION_NAME, new BigDecimal("0.00"), 99, "质量", false);

    // fixture 自检：空维度确实进了库，且草稿里正好一个（用"数出来的东西"正面断言，不是"没有反例"）
    Long emptyDimensions =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM dimension d WHERE d.template_version_id = ?"
                + " AND NOT EXISTS (SELECT 1 FROM indicator i WHERE i.dimension_id = d.id)",
            Long.class,
            versionId);
    assertThat(emptyDimensions).as("fixture 自检：草稿里应当恰好有 1 个无指标维度").isEqualTo(1L);
    Long totalDimensions =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM dimension WHERE template_version_id = ?", Long.class, versionId);
    assertThat(totalDimensions).as("fixture 自检：4 个原有维度 + 新增的这个").isEqualTo(5L);

    assertThatThrownBy(() -> templateService.publish(versionId, requireVersion(versionId).getVersion()))
        .isInstanceOf(BizException.class)
        // 报文里点名的必须是**这个空维度**：它只能来自 SQL 的 LEFT JOIN 把 `indicatorCount = 0`
        // 的那一行带回来（INNER JOIN 时该行消失，这里会变成"发布成功"）
        .hasMessageContaining(EMPTY_DIMENSION_NAME)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_DIMENSION_EMPTY);
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

    assertThat(effective.versionId()).isNotEqualTo(publishedVersionId);
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

  // ------------------------------------------- V9 回填的两个核心守卫（03 §31.7.11 / §31.7.12 补）

  /**
   * 03 §31.7.12 的 P2：`V9` 的**核心守卫**（"不覆盖已有文案"）原先**没有任何用例守着**——全仓只有
   * `defaultTemplateMatchesAppendixA` 那一处结构断言，而把回填的 `WHERE` 简化成 `WHERE code = …`
   * （去掉锚点 JOIN 与 `standard IS NULL`）之后它照样全绿。这里把迁移脚本**当一段可重放的 SQL 再跑一遍**
   * （脚本在容器启动时已经跑过一次，此后 `standard` 全非空，那些条件就再没被任何用例碰过）。
   */
  @Test
  @DisplayName("V9 回填①：用户已写好的文案**不被覆盖**，同一版本里 `standard IS NULL` 的行确实被填上（真库重放迁移文件）")
  void v9BackfillKeepsCustomStandardAndFillsNullOnes() throws Exception {
    long draftId = templateService.ensureEditable(publishedDefaultVersionId()).versionId();

    // 造出 §31.7.11 描述的状态：一份"V9 上线之前就存在的派生草稿"——5 行全空，其中一行用户已自己填过
    assertThat(clearV9Standards(draftId))
        .as("fixture 自检：草稿里应当有 5 个待回填的定性指标")
        .isEqualTo(V9_BACKFILLED_CODES.size());
    jdbcTemplate.update(
        "UPDATE indicator SET standard = ? WHERE template_version_id = ? AND code = ?",
        CUSTOM_STANDARD,
        draftId,
        "PAYMENT_COOP");
    assertThat(nonNullV9Standards(draftId))
        .as("fixture 自检：此刻只有 1 行非空（用户自己写的那行）")
        .isEqualTo(1L);

    // 执行**迁移文件本体**（回到 V9 的处境：这一版还有 4 行是 NULL）
    int backfilledRows = runV9Backfill();

    assertThat(standardOf(draftId, "PAYMENT_COOP"))
        .as("用户自己写的文案被 V9 覆盖了——`standard IS NULL` 这个守卫失效")
        .isEqualTo(CUSTOM_STANDARD);
    assertThat(nonNullV9Standards(draftId))
        .as("守卫的另一半：同一版本里空着的那 4 行**必须**被填上（否则'没被覆盖'可能只是它什么都没干）")
        .isEqualTo(5L);
    assertThat(backfilledRows)
        .as("回填必须真的改了行（判据形状：数出来的东西要 > 0）")
        .isGreaterThanOrEqualTo(4);
    // 内容也要对，不只是非空——CASE 里 code↔文案配错（张冠李戴）在这里变红
    assertThat(standardOf(draftId, "ISSUE_RESPONSE")).startsWith("5=").contains("响应");
    assertThat(standardOf(draftId, "CONTACT_STABILITY")).contains("更换");
  }

  /**
   * 03 §31.7.11：`V9` 的锚点从"只回填 v1"改成了"按 `V8` 种下的指纹认定血统版本 + `standard IS NULL`"，
   * 因此**副本版本**（`copied_from_version_id` 指向种子那份）也会被回填。这条守的就是那个覆盖面：
   * 若把条件收窄回"只改 v1"，副本的 5 行会永远空着，而"编辑默认模板"恰好会复用到那份草稿。
   */
  @Test
  @DisplayName("V9 回填②：**副本版本**（copied_from_version_id 指向种子那份）的 5 行也一起被回填，内容逐条正确")
  void v9BackfillCoversCopiedDraftVersion() throws Exception {
    long seedVersionId = publishedDefaultVersionId();
    long copyVersionId = templateService.ensureEditable(seedVersionId).versionId();
    assertThat(copiedFrom(copyVersionId))
        .as("fixture 自检：这一份必须是**副本**版本，否则本条用例测不到'副本也会被回填'")
        .isEqualTo(seedVersionId);

    assertThat(clearV9Standards(copyVersionId))
        .as("fixture 自检：副本里应当有 5 个定性指标")
        .isEqualTo(V9_BACKFILLED_CODES.size());

    int backfilledRows = runV9Backfill();

    List<String> filled =
        jdbcTemplate.queryForList(
            "SELECT code FROM indicator WHERE template_version_id = ? AND standard IS NOT NULL"
                + " AND code IN (?, ?, ?, ?, ?) ORDER BY code",
            String.class,
            copyVersionId,
            V9_BACKFILLED_CODES.get(0),
            V9_BACKFILLED_CODES.get(1),
            V9_BACKFILLED_CODES.get(2),
            V9_BACKFILLED_CODES.get(3),
            V9_BACKFILLED_CODES.get(4));
    assertThat(filled)
        .as("副本版本的 5 行必须全部被回填（只填 v1 时这里是空的）")
        .hasSize(5)
        .containsExactlyInAnyOrderElementsOf(V9_BACKFILLED_CODES);
    assertThat(backfilledRows).as("回填必须真的改了行（> 0）").isGreaterThanOrEqualTo(5);
    assertThat(standardOf(copyVersionId, "PAYMENT_COOP"))
        .as("副本里的文案内容也要对，不只是非空")
        .startsWith("5=")
        .contains("账期");
  }

  // ---------------------------------------------------------------- 辅助

  private long publishedDefaultVersionId() {
    // ⚠️ 03 §31.7.14 的 P2-10：原来这条查询**不按模板名过滤、也没有 ORDER BY**，只写了
    // `version_no = 1 AND status = 'PUBLISHED' LIMIT 1`。共享容器里跑完全量用例后会有多份
    // `(version_no = 1, PUBLISHED)`（每个新建模板都是一条），取到哪一条全凭"全表扫描按聚簇索引顺序"
    // 这个**未指定**的行为——当前恰好取到 V8 那份，换个执行计划就会取到别的模板、用例假红。
    // 现在按模板名 + id 升序把范围收敛到本类认定的那份种子。
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT v.id FROM template_version v JOIN template t ON t.id = v.template_id"
                + " WHERE t.name = ? AND v.version_no = 1 AND v.status = 'PUBLISHED'"
                + " ORDER BY v.id LIMIT 1",
            Long.class,
            DEFAULT_TEMPLATE_NAME);
    assertThat(id).as("V8 应预置名为【%s】的已发布 v1", DEFAULT_TEMPLATE_NAME).isNotNull();
    return id;
  }

  /** 读迁移脚本执行；返回**回填那条 UPDATE 的实际影响行数**（0 行本身就是"什么都没做"的信号）。 */
  private int runV9Backfill() throws IOException {
    String raw =
        new String(
            new ClassPathResource(V9_MIGRATION_FILE).getContentAsByteArray(), StandardCharsets.UTF_8);
    // 拆句：剥掉 `--` 行注释后按 ASCII `;` 切。脚本里的档位文案用的是**全角** `；`、也没有别的
    // ASCII 分号，所以这个拆句器够用——下面两条断言是对**拆句器自己**的正例检查（拆不出语句时必须响亮失败）。
    StringBuilder withoutComments = new StringBuilder();
    for (String line : raw.split("\r?\n")) {
      int comment = line.indexOf("--");
      withoutComments.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
    }
    List<String> statements =
        Arrays.stream(withoutComments.toString().split(";"))
            .map(String::trim)
            .filter(statement -> !statement.isEmpty())
            .toList();
    // ⚠️ 这里**不钉语句条数**：V9 的语句形态会随"要不要加锚点自检"变化（曾经是 4 条：建临时表 +
    //    INSERT + UPDATE + DROP；现在是 2 条：锚点自检 SELECT + UPDATE）。钉死条数的断言会在脚本正常
    //    演进时变红，却一条真正的缺陷都拦不住（03 §31.7.15 那类"断言写偏"）。改为断言**意图**：
    //    拆句器真的拆出了那条 `UPDATE indicator`，且**恰好一条**（漏拆 / 拆重都必须红）。
    assertThat(statements)
        .as("必须从 %s 里真的拆出语句（拆句器失效时不能静默通过）", V9_MIGRATION_FILE)
        .isNotEmpty();
    assertThat(
            statements.stream()
                .filter(s -> s.regionMatches(true, 0, "UPDATE indicator", 0, "UPDATE indicator".length()))
                .count())
        .as("回填那条 UPDATE 必须被拆出来、且只有一条（否则本用例会静默什么都不做）")
        .isEqualTo(1L);

    int affected = -1;
    for (String statement : statements) {
      if (statement.regionMatches(true, 0, "UPDATE indicator", 0, "UPDATE indicator".length())) {
        affected = jdbcTemplate.update(statement);
      } else {
        // 锚点自检那条 SELECT：原样执行（锚点缺失时它会自己抛 ERROR 1242，本用例跑在 V8 种子上 ⇒ 不会）
        jdbcTemplate.execute(statement);
      }
    }
    assertThat(affected).as("迁移文件里必须有一条回填 UPDATE（否则上面拆句或改名了）").isNotEqualTo(-1);
    return affected;
  }

  /** 把某个版本的 5 个定性指标文案清成 `NULL`（复现 V9 上线前那份派生草稿的状态）。 */
  private int clearV9Standards(long versionId) {
    return jdbcTemplate.update(
        "UPDATE indicator SET standard = NULL WHERE template_version_id = ?"
            + " AND code IN (?, ?, ?, ?, ?)",
        versionId,
        V9_BACKFILLED_CODES.get(0),
        V9_BACKFILLED_CODES.get(1),
        V9_BACKFILLED_CODES.get(2),
        V9_BACKFILLED_CODES.get(3),
        V9_BACKFILLED_CODES.get(4));
  }

  /** 某个版本里**非空**的定性文案行数（"数出来的东西"断言用）。 */
  private long nonNullV9Standards(long versionId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM indicator WHERE template_version_id = ? AND standard IS NOT NULL"
                + " AND code IN (?, ?, ?, ?, ?)",
            Long.class,
            versionId,
            V9_BACKFILLED_CODES.get(0),
            V9_BACKFILLED_CODES.get(1),
            V9_BACKFILLED_CODES.get(2),
            V9_BACKFILLED_CODES.get(3),
            V9_BACKFILLED_CODES.get(4));
    return count == null ? 0L : count;
  }

  private String standardOf(long versionId, String code) {
    return jdbcTemplate.queryForObject(
        "SELECT standard FROM indicator WHERE template_version_id = ? AND code = ?",
        String.class,
        versionId,
        code);
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
