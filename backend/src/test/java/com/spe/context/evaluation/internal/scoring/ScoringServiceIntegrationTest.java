package com.spe.context.evaluation.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.events.ApprovalRoundStartedEvent;
import com.spe.context.evaluation.internal.scoring.calculator.ScoreSheetResult;
import com.spe.context.evaluation.internal.task.TaskDraftCommand;
import com.spe.context.evaluation.internal.task.TaskService;
import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * W4 评分域的集成用例（真库 + 真事务）：暂存（E7/E8）、试算（E18，不落库）、提交（T2 / E9）、可见性（E13）。
 *
 * <p>三条口径在本类里被反复钉住：① 写入只有一个入口（`ScoreWriter`）；② 试算与提交调**同一个**
 * `TScoreCalculator`；③ 不完整维度**不给分**（null，不是 0）。
 *
 * <p>{@code @RecordApplicationEvents} 是给 P1-3 的两条用例用的：事件的**载荷**（round、approverId）
 * 只有从发布侧收下来才判得了，库里没有消费者时（W2 之前）数据库里查不到任何痕迹。
 */
@RecordApplicationEvents
class ScoringServiceIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicInteger PERIOD_SEQ = new AtomicInteger(2091);

  @Autowired private ScoringService scoringService;
  @Autowired private ScoreWriter scoreWriter;
  @Autowired private TaskService taskService;
  @Autowired private ApplicationEvents applicationEvents;

  // ---------- P1-1 用的确定性交错闸门（03 §38） ----------

  /**
   * 测试专用的"停在两条语句之间"的闸门。
   *
   * <p><b>为什么必须注入拦截器，而不是"两条线程一起冲"</b>：要证的交错是
   * 「A 的 read view 建立之后、取锁之前，B 提交了」。那个窗口只有几条语句宽，靠调度撞不出来；
   * 靠 sleep 等则既慢又假（`06 §8` 禁无理由 sleep）。所以让拦截器在 A 命中指定语句后停住，
   * 测试线程趁这个确定的点提交 B，再放行 A——**红了就是因为判据，不是因为运气**。
   */
  @TestConfiguration
  static class InterleavingHarness {

    @Bean
    Interceptor parkAfterReadInterceptor() {
      return new ParkAfterReadInterceptor();
    }
  }

  /** 命中该语句 id 后缀的第一次执行会停一下（一次性 ⇒ 其它用例与同事务内的第二次读都不受影响）。 */
  private static volatile String parkOnStatement;

  private static final CyclicBarrier PARKED = new CyclicBarrier(2);

  private static volatile CountDownLatch resumeGate = new CountDownLatch(1);

  @Intercepts({
    @Signature(
        type = Executor.class,
        method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(
        type = Executor.class,
        method = "query",
        args = {
          MappedStatement.class,
          Object.class,
          RowBounds.class,
          ResultHandler.class,
          CacheKey.class,
          BoundSql.class
        })
  })
  public static class ParkAfterReadInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
      MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
      String target = parkOnStatement;
      if (target == null || !statement.getId().endsWith(target)) {
        return invocation.proceed();
      }
      parkOnStatement = null;
      Object result = invocation.proceed();
      PARKED.await(30, TimeUnit.SECONDS);
      resumeGate.await(30, TimeUnit.SECONDS);
      return result;
    }

    @Override
    public Object plugin(Object target) {
      return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
      // 这个闸门不需要配置项
    }
  }

  @AfterEach
  void releaseInterleavingGate() {
    parkOnStatement = null;
    // 用例中途失败时也不要把工作线程留在闸门里
    resumeGate.countDown();
    resumeGate = new CountDownLatch(1);
  }

  // ---------- W4.1 暂存与试算 ----------

  @Test
  @DisplayName("W4.1 暂存：经 ScoreWriter 落库且**写入时就算换算分**（E8：score_value 不是提交时才落）")
  void saveScoresConvertsAtWriteTime() {
    Fixture fixture = publishedTask();
    long sheetId = fixture.sheetIdOf("质量");

    int written = scoringService.saveScores(sheetId, fixture.operator(), fixture.qualityValues(), List.of());

    assertThat(written).as("写了 4 个指标").isEqualTo(4);
    Map<String, BigDecimal> stored = fixture.storedScores(sheetId);
    assertThat(stored)
        .as("换算分按 01 §6.5（98.50 / 100 / 96 / 92）——写入时就落库")
        .containsEntry("IQC_PASS_RATE", new BigDecimal("98.50"))
        .containsEntry("QUALITY_ACCIDENT", new BigDecimal("100.00"))
        .containsEntry("IQC_ON_TIME_RATE", new BigDecimal("96.00"))
        .containsEntry("QMS_AUDIT_SCORE", new BigDecimal("92.00"));
    assertThat(fixture.sourceOf(sheetId, "IQC_PASS_RATE")).as("来源记 MANUAL").isEqualTo("MANUAL");
    assertThat(fixture.importBatchOf(sheetId, "IQC_PASS_RATE")).as("手工写入不带批次号").isNull();
  }

  // ---------- P2-15：清空一格进契约（03 §38；18 §6 裁决点 6 拍定"发显式 null"） ----------

  /**
   * 这条用例治的是原先那条用户可见的错行为：**删值 → 提示已保存 0 个指标 → 刷新后旧值复活**。
   * 契约层当时表达不了"清空"，所以 `ScoreWriter.buildRows` 直接把 null 跳过。
   */
  @Test
  @DisplayName("P2-15 清空：显式点名的格子真的从库里消失")
  void clearingACellRemovesTheRow() {
    Fixture fixture = publishedTask();
    long sheetId = fixture.sheetIdOf("质量");
    scoringService.saveScores(sheetId, fixture.operator(), fixture.qualityValues(), List.of());
    assertThat(fixture.storedScores(sheetId)).as("前置：四格都有值").hasSize(4);

    int changed =
        scoringService.saveScores(
            sheetId, fixture.operator(), Map.of(), List.of(fixture.indicatorId("质量", "QMS_AUDIT_SCORE")));

    assertThat(changed).as("删掉一行 ⇒ 算一次改动").isEqualTo(1);
    assertThat(fixture.storedScores(sheetId))
        .as("清空必须真的落库")
        .doesNotContainKey("QMS_AUDIT_SCORE")
        .hasSize(3);
  }

  /**
   * "删一个本来就没值的格子"是**幂等的成功**，不是 3xxx：`06 §2.4` 管的是状态迁移的守卫 UPDATE，
   * 而这里没有状态机。给它补一个 3001 就是发明了一种不存在的失败。
   */
  @Test
  @DisplayName("P2-15 幂等：清空本来就没值的格子 ⇒ 0 次改动、不报错")
  void clearingAnEmptyCellIsNotAnError() {
    Fixture fixture = publishedTask();
    long sheetId = fixture.sheetIdOf("质量");

    int changed =
        scoringService.saveScores(
            sheetId, fixture.operator(), Map.of(), List.of(fixture.indicatorId("质量", "QMS_AUDIT_SCORE")));

    assertThat(changed).isZero();
    assertThat(fixture.storedScores(sheetId)).isEmpty();
  }

  /**
   * 顺带收紧的一条**既有缺口**（03 §39 登记）：`ScoreWriter` 的归属校验原先按"整个模板版本"收集合，
   * 于是给一张质量单写进"成本"的指标也能过——库里会多一条本维度根本不展示的幽灵行。
   * P2-15 的删除路径让同一个洞变成"删一条看不见的行"，所以两条一起按**这张单的维度**收。
   */
  @Test
  @DisplayName("P2-15 归属：写或清**别的维度**的指标 ⇒ 2015，不落幽灵行")
  void otherDimensionIndicatorIsRejectedForWriteAndClear() {
    Fixture fixture = publishedTask();
    long qualitySheet = fixture.sheetIdOf("质量");
    long costIndicator = fixture.indicatorId("成本", "COST_DOWN_RATE");

    assertThatThrownBy(
            () ->
                scoringService.saveScores(
                    qualitySheet, fixture.operator(), Map.of(costIndicator, new BigDecimal("80")), List.of()))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND);
    assertThatThrownBy(
            () -> scoringService.saveScores(qualitySheet, fixture.operator(), Map.of(), List.of(costIndicator)))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_INDICATOR_NOT_FOUND);
    assertThat(fixture.storedScores(qualitySheet)).as("两次都被拒 ⇒ 一张单子都没多出行").isEmpty();
  }

  @Test
  @DisplayName("P2-15 与提交：清空后提交 ⇒ 2024 且点名那个编码（「删掉」与「从没填」在提交侧同口径）")
  void submitAfterClearReportsTheMissingCode() {
    Fixture fixture = publishedTask();
    long sheetId = fixture.sheetIdOf("质量");
    scoringService.saveScores(sheetId, fixture.operator(), fixture.qualityValues(), List.of());
    scoringService.saveScores(
        sheetId, fixture.operator(), Map.of(), List.of(fixture.indicatorId("质量", "QMS_AUDIT_SCORE")));

    assertThatThrownBy(() -> scoringService.submit(sheetId, fixture.operator(), 0))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("QMS_AUDIT_SCORE");
    assertThat(fixture.sheetStatus(sheetId)).as("被拒后仍是评分中、没有半提交").isEqualTo("SCORING");
  }

  @Test
  @DisplayName("W4.1 试算不落库：传候选值算得 96.35，但库里一行都不多、值也不变")
  void previewComputesWithoutPersisting() {
    Fixture fixture = publishedTask();
    long sheetId = fixture.sheetIdOf("质量");
    long rowsBefore = fixture.scoreRowCount(fixture.taskId());

    SheetPreview preview =
        scoringService.preview(sheetId, fixture.operator(), fixture.qualityValues());

    assertThat(preview.taskSupplier().sheets())
        .filteredOn(sheet -> sheet.dimensionName().equals("质量"))
        .singleElement()
        .satisfies(
            sheet -> {
              assertThat(sheet.complete()).isTrue();
              assertThat(sheet.score()).as("01 §6.5 的质量维度分").isEqualByComparingTo("96.35");
              assertThat(sheet.grade()).isEqualTo("A");
            });
    assertThat(fixture.scoreRowCount(fixture.taskId())).as("试算不落库").isEqualTo(rowsBefore);
    assertThat(fixture.storedScores(sheetId)).as("明细也不该被写进去").isEmpty();
  }

  @Test
  @DisplayName("W4.1 试算与暂存后的重算一致：四个维度都填满 ⇒ 总分 90.82（同一个 ScoreCalculator）")
  void previewMatchesSavedRecompute() {
    Fixture fixture = publishedTask();
    for (String dimension : List.of("质量", "交付", "成本", "服务")) {
      scoringService.saveScores(
          fixture.sheetIdOf(dimension), fixture.operator(), fixture.valuesOf(dimension), List.of());
    }

    SheetPreview preview =
        scoringService.preview(fixture.sheetIdOf("质量"), fixture.operator(), Map.of());

    assertThat(preview.taskSupplier().complete()).as("四个维度都填了").isTrue();
    assertThat(preview.taskSupplier().totalScore()).as("01 §6.5 的总分").isEqualByComparingTo("90.82");
    assertThat(preview.taskSupplier().totalGrade()).isEqualTo("A");
    assertThat(preview.taskSupplier().sheets())
        .extracting(ScoreSheetResult::dimensionName, ScoreSheetResult::score, ScoreSheetResult::grade)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("质量", new BigDecimal("96.35"), "A"),
            org.assertj.core.groups.Tuple.tuple("交付", new BigDecimal("94.27"), "A"),
            org.assertj.core.groups.Tuple.tuple("成本", new BigDecimal("76.00"), "C"),
            org.assertj.core.groups.Tuple.tuple("服务", new BigDecimal("88.00"), "B"));
  }

  // ---------- W4.2 提交 ----------

  @Test
  @DisplayName("W4.2 缺值不许提交：message 要点名缺哪几个指标编码（01 E4）")
  void submitRejectsIncompleteDimensionNamingMissingCodes() {
    Fixture fixture = publishedTask();
    long sheetId = fixture.sheetIdOf("质量");
    Map<Long, BigDecimal> partial = new LinkedHashMap<>(fixture.qualityValues());
    partial.remove(fixture.indicatorId("质量", "QMS_AUDIT_SCORE"));
    scoringService.saveScores(sheetId, fixture.operator(), partial, List.of());

    assertThatThrownBy(() -> scoringService.submit(sheetId, fixture.operator(), 0))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("QMS_AUDIT_SCORE");
    assertThat(fixture.sheetStatus(sheetId)).as("被拒后仍是评分中").isEqualTo("SCORING");
  }

  @Test
  @DisplayName("W4.2 提交锁定评分单：最后一张提交时评估单转待审批，之后再改/再提交都被拒")
  void submitLocksSheetAndMovesTaskSupplierOnLastSheet() {
    Fixture fixture = publishedTask();
    for (String dimension : List.of("质量", "交付", "成本")) {
      scoringService.saveScores(
          fixture.sheetIdOf(dimension), fixture.operator(), fixture.valuesOf(dimension), List.of());
    }
    for (String dimension : List.of("质量", "交付", "成本")) {
      SubmitResult result = scoringService.submit(fixture.sheetIdOf(dimension), fixture.operator(), 0);
      assertThat(result.taskSupplierStatus())
          .as("还有维度没提交 ⇒ 评估单仍是评分中（%s）", dimension)
          .isEqualTo("SCORING");
    }

    long lastSheet = fixture.sheetIdOf("服务");
    scoringService.saveScores(lastSheet, fixture.operator(), fixture.valuesOf("服务"), List.of());
    SubmitResult last = scoringService.submit(lastSheet, fixture.operator(), 0);

    assertThat(last.taskSupplierStatus()).as("最后一张提交 ⇒ 评估单转待审批").isEqualTo("PENDING_APPROVAL");
    assertThat(fixture.taskSupplierStatus()).isEqualTo("PENDING_APPROVAL");
    assertThat(fixture.taskSupplierCurrentNode()).as("节点序号置 1（05 §5.5 T2）").isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'SHEET_SUBMIT' and target_id = ?",
                Long.class,
                fixture.taskSupplierId()))
        .as("四次提交各留一条痕（06 §2.8）")
        .isEqualTo(4L);

    assertThatThrownBy(() -> scoringService.saveScores(lastSheet, fixture.operator(), fixture.valuesOf("服务"), List.of()))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .as("提交后不可再改")
        .isEqualTo(ErrorCode.TASK_STATUS_INVALID);
    assertThatThrownBy(() -> scoringService.submit(lastSheet, fixture.operator(), 1))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .as("提交后不可再提交")
        .isEqualTo(ErrorCode.TASK_STATUS_INVALID);
  }

  @Test
  @DisplayName("W4.2 E9 第 5 步：评估单已被别人迁移时，最后一张的提交**整体回滚**（3001，sheet 仍评分中）")
  void lastSubmitRollsBackWhenTaskSupplierAlreadyMigrated() {
    Fixture fixture = publishedTask();
    for (String dimension : List.of("质量", "交付", "成本")) {
      scoringService.saveScores(
          fixture.sheetIdOf(dimension), fixture.operator(), fixture.valuesOf(dimension), List.of());
      scoringService.submit(fixture.sheetIdOf(dimension), fixture.operator(), 0);
    }
    long lastSheet = fixture.sheetIdOf("服务");
    scoringService.saveScores(lastSheet, fixture.operator(), fixture.valuesOf("服务"), List.of());
    // 模拟"另一个提交事务已经迁移过评估单"：把状态挪走，让 markPendingApproval 的守卫命中 0 行
    jdbcTemplate.update("update task_supplier set status = 'APPROVING' where id = ?", fixture.taskSupplierId());

    assertThatThrownBy(() -> scoringService.submit(lastSheet, fixture.operator(), 0))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .as("0 行必须抛 3001，而不是静默放过（13 §E9）")
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    assertThat(fixture.sheetStatus(lastSheet))
        .as("整体回滚：这张评分单必须仍是评分中，不能留下「已提交」的假象")
        .isEqualTo("SCORING");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'SHEET_SUBMIT' and target_id = ?",
                Long.class,
                fixture.taskSupplierId()))
        .as("失败的那次不留审计（审计与业务同事务）")
        .isEqualTo(3L);
  }

  @Test
  @DisplayName("P1-3 最后一张提交发的事件带的是库里的 round，不是写死的 1")
  void lastSubmitPublishesTheCurrentRound() {
    Fixture fixture = publishedTask();
    for (String dimension : List.of("质量", "交付", "成本")) {
      long sheetId = fixture.sheetIdOf(dimension);
      scoringService.saveScores(sheetId, fixture.operator(), fixture.valuesOf(dimension), List.of());
      scoringService.submit(sheetId, fixture.operator(), 0);
    }
    long lastSheet = fixture.sheetIdOf("服务");
    scoringService.saveScores(lastSheet, fixture.operator(), fixture.valuesOf("服务"), List.of());
    // 模拟"驳回重提之后的第二轮"：T5 的守卫 UPDATE 会把 round +1（W3 落地），这里先把它挪到 2。
    // 写死 1 的实现照样能跑完前面的用例——只有这一条会红，因为它断的是**载荷的值**。
    jdbcTemplate.update("update task_supplier set round = 2 where id = ?", fixture.taskSupplierId());

    SubmitResult last = scoringService.submit(lastSheet, fixture.operator(), 0);

    assertThat(last.taskSupplierStatus()).isEqualTo("PENDING_APPROVAL");
    List<ApprovalRoundStartedEvent> events =
        applicationEvents.stream(ApprovalRoundStartedEvent.class).toList();
    assertThat(events)
        .as("本用例的提交必须且只能发一个审批轮次事件，实际=%s", events)
        .hasSize(1);
    assertThat(events.get(0).round())
        .as("round 必须取自 task_supplier 的当前值（写死 1 就是 03 §38 的 P1-3）")
        .isEqualTo(2);
    assertThat(events.get(0).taskSupplierId())
        .as("事件指向的必须是本用例那张评估单")
        .isEqualTo(fixture.taskSupplierId());
  }

  @Test
  @DisplayName("P1-3 第一级审批节点缺失：提交抛 2023 且整体回滚，绝不发 uid=0 的事件")
  void submitFailsLoudlyWhenFirstApproverIsMissing() {
    Fixture fixture = publishedTask();
    for (String dimension : List.of("质量", "交付", "成本")) {
      long sheetId = fixture.sheetIdOf(dimension);
      scoringService.saveScores(sheetId, fixture.operator(), fixture.valuesOf(dimension), List.of());
      scoringService.submit(sheetId, fixture.operator(), 0);
    }
    long lastSheet = fixture.sheetIdOf("服务");
    scoringService.saveScores(lastSheet, fixture.operator(), fixture.valuesOf("服务"), List.of());
    String statusBefore = fixture.taskSupplierStatus();
    int versionBefore = fixture.taskSupplierVersion();
    int nodeBefore = fixture.taskSupplierCurrentNode();
    // publish 保证过 seq=1 存在（TaskService:306-309 与 :555-560）⇒ 删掉它就等于"数据被旁路改坏"，
    // 这时**必须响亮失败**：兜成 uid=0 会让 W2 的监听器写出一行没人收到的通知（CLAUDE 默认约定第 8 条）
    jdbcTemplate.update("delete from approval_node where task_id = ? and seq = 1", fixture.taskId());

    assertThatThrownBy(() -> scoringService.submit(lastSheet, fixture.operator(), 0))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("taskId=" + fixture.taskId())
        .extracting(ex -> ((BizException) ex).errorCode())
        .as("2023 的默认文案会说「人员不存在或已停用」，但评估人什么人都没指定——message 必须覆盖")
        .isEqualTo(ErrorCode.USER_NOT_ELIGIBLE);

    assertThat(fixture.sheetStatus(lastSheet)).as("回滚：这张评分单仍应是评分中").isEqualTo("SCORING");
    assertThat(fixture.taskSupplierStatus())
        .as("回滚：评估单状态不得被迁移")
        .isEqualTo(statusBefore);
    assertThat(fixture.taskSupplierCurrentNode())
        .as("回滚：current_node 不得被置 1")
        .isEqualTo(nodeBefore);
    assertThat(fixture.taskSupplierVersion())
        .as("回滚：version 不得被消耗")
        .isEqualTo(versionBefore);
    assertThat(applicationEvents.stream(ApprovalRoundStartedEvent.class).count())
        .as("查不到审批人就不许发事件——发了就是通知一个不存在的 uid")
        .isZero();
  }

  @Test
  @DisplayName("P1-1 取锁之前被别人补上的分值必须看得见：A 不许按自己钉好的快照判「还有指标未填写」")
  void submitSeesValuesWrittenAfterItsReadViewWasPinned() throws Exception {
    Fixture fixture = publishedTask();
    long sheetId = fixture.sheetIdOf("质量");
    Map<Long, BigDecimal> partial = new LinkedHashMap<>(fixture.qualityValues());
    partial.remove(fixture.indicatorId("质量", "QMS_AUDIT_SCORE"));
    scoringService.saveScores(sheetId, fixture.operator(), partial, List.of());

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      parkOnStatement = "SheetMapper.findContextById";
      resumeGate = new CountDownLatch(1);
      Future<SubmitResult> submitting =
          pool.submit(() -> scoringService.submit(sheetId, fixture.operator(), 0));
      PARKED.await(30, TimeUnit.SECONDS); // A 停在 :170 与 :174 之间：read view 已经钉在这里

      // B 趁这个窗口把最后一格补上并提交。save 只 upsert score 行、**不动 sheet.version**，
      // 所以 A 的乐观锁守卫挡不住它——能不能看见，取决于 A 那一读是不是当前读
      scoringService.saveScores(sheetId, fixture.operator(), fixture.qualityValues(), List.of());
      resumeGate.countDown();

      SubmitResult result = submitting.get(30, TimeUnit.SECONDS);
      assertThat(result.submittedIndicators())
          .as("A 必须按 B 提交后的 4 格判定；按 :170 那份快照判会抛 2024「该维度还有指标未填写」")
          .isEqualTo(4);
      assertThat(fixture.sheetStatus(sheetId)).as("提交已生效").isEqualTo("SUBMITTED");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("W4.2 并发提交同一评估单的两张评分单：提交被串行化（不死锁），评估单只迁移一次")
  void concurrentSubmitConvergesOnSingleMigration() throws Exception {
    Fixture fixture = publishedTask();
    long first = fixture.sheetIdOf("质量");
    long second = fixture.sheetIdOf("交付");
    // 先把另外两个维度按顺序提交掉：本用例要制造的是"**最后一张**评分单的并发提交"，
    // 而不是"只提交了两张、另外两张还没提交"（那样永远不会迁移，也就测不到收敛点）
    for (String dimension : List.of("成本", "服务")) {
      long sheetId = fixture.sheetIdOf(dimension);
      scoringService.saveScores(sheetId, fixture.operator(), fixture.valuesOf(dimension), List.of());
      scoringService.submit(sheetId, fixture.operator(), 0);
    }
    scoringService.saveScores(first, fixture.operator(), fixture.valuesOf("质量"), List.of());
    scoringService.saveScores(second, fixture.operator(), fixture.valuesOf("交付"), List.of());

    Map<Long, Integer> versions = Map.of(first, 0, second, 0);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<String>> tasks = new ArrayList<>();
    for (long sheetId : List.of(first, second)) {
      tasks.add(
          () -> {
            start.await();
            try {
              scoringService.submit(sheetId, fixture.operator(), versions.get(sheetId));
              return "OK";
            } catch (BizException ex) {
              return ex.errorCode().name();
            }
          });
    }
    Future<String> f1 = pool.submit(tasks.get(0));
    Future<String> f2 = pool.submit(tasks.get(1));
    start.countDown();
    List<String> results = List.of(f1.get(60, TimeUnit.SECONDS), f2.get(60, TimeUnit.SECONDS));
    pool.shutdown();

    // ⚠️ 期望值的口径（与 13 §E1 判据 8 的初稿不同，已回写 13 §E9）：同一评估单的提交被**串行化**在
    //    评估单行锁上，因此后来者读到的是"兄弟行已提交"，两次提交都成功、评估单只迁移一次。
    //    初稿写的"另一方得 3001"在实现时会**死锁**（见 04-090）；3001 的路径由"评估单已被外部迁移"
    //    那条确定性用例覆盖（lastSubmitRollsBackWhenTaskSupplierAlreadyMigrated）。
    assertThat(results).as("两次提交都应当成功（没有一次是白跑的）").containsOnly("OK");
    assertThat(fixture.taskSupplierStatus()).as("评估单只迁移一次").isEqualTo("PENDING_APPROVAL");
    assertThat(
            jdbcTemplate.queryForObject(
                "select version from task_supplier where id = ?", Integer.class, fixture.taskSupplierId()))
        .as("迁移恰好一次 ⇒ version 恰好 +1")
        .isEqualTo(1);
    assertThat(List.of(fixture.sheetStatus(first), fixture.sheetStatus(second)))
        .as("两张评分单都落 SUBMITTED，没有半成品")
        .containsOnly("SUBMITTED");
  }

  // ---------- W4.4 可见性 ----------

  @Test
  @DisplayName("W4.4 别人看不到也改不了我的评分单：读/写/提交都 4002（第二层过滤在 Service 内）")
  void otherEvaluatorCannotReadOrWrite() {
    Fixture fixture = publishedTask();
    long other = fixture.otherEvaluator();
    long sheetId = fixture.sheetIdOf("质量");

    assertThatThrownBy(() -> scoringService.readSheet(sheetId, other))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SHEET_NOT_ACCESSIBLE);
    assertThatThrownBy(() -> scoringService.saveScores(sheetId, other, fixture.qualityValues(), List.of()))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SHEET_NOT_ACCESSIBLE);
    assertThatThrownBy(() -> scoringService.submit(sheetId, other, 0))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SHEET_NOT_ACCESSIBLE);
    assertThat(fixture.scoreRowCount(fixture.taskId())).as("越权写入没有落任何一行").isZero();
  }

  // ---------- 夹具 ----------

  /** 发布一个任务并把**全部评分单改派给同一个评估人**，让用例可以顺畅地逐维度写/提交。 */
  private final class Fixture {

    private final int year;
    private final long taskId;
    private final long taskSupplierId;
    private final long operator;
    private final long other;
    private final Map<String, Long> sheetIdsByDimension = new LinkedHashMap<>();

    private Fixture(int year, long taskId, long taskSupplierId, long operator, long other) {
      this.year = year;
      this.taskId = taskId;
      this.taskSupplierId = taskSupplierId;
      this.operator = operator;
      this.other = other;
      jdbcTemplate
          .queryForList(
              "select d.name, s.id from sheet s join task_supplier ts on ts.id = s.task_supplier_id"
                  + " join dimension d on d.id = s.dimension_id where ts.task_id = ?",
              taskId)
          .forEach(row -> sheetIdsByDimension.put((String) row.get("name"), (Long) row.get("id")));
    }

    long taskId() {
      return taskId;
    }

    long taskSupplierId() {
      return taskSupplierId;
    }

    long operator() {
      return operator;
    }

    long otherEvaluator() {
      return other;
    }

    long sheetIdOf(String dimension) {
      Long id = sheetIdsByDimension.get(dimension);
      assertThat(id).as("维度 %s 必须有评分单", dimension).isNotNull();
      return id;
    }

    long indicatorId(String dimension, String code) {
      Long id =
          jdbcTemplate.queryForObject(
              "select i.id from indicator i join dimension d on d.id = i.dimension_id"
                  + " where d.name = ? and i.code = ?",
              Long.class,
              dimension,
              code);
      return id == null ? -1 : id;
    }

    /** 01 §6.5 的录入值（按维度）。 */
    Map<Long, BigDecimal> valuesOf(String dimension) {
      return switch (dimension) {
        case "质量" -> qualityValues();
        case "交付" -> values("交付", "OTD_RATE", "95.08", "FILL_RATE", "99.20", "URGENT_RESPONSE", "4");
        case "成本" -> values("成本", "COST_DOWN_RATE", "2.40", "PRICE_COMPETITIVENESS", "4", "PAYMENT_COOP", "3");
        case "服务" -> values("服务", "ISSUE_RESPONSE", "5", "ABNORMAL_COOP", "4", "CONTACT_STABILITY", "4");
        default -> throw new IllegalArgumentException(dimension);
      };
    }

    Map<Long, BigDecimal> qualityValues() {
      return values(
          "质量",
          "IQC_PASS_RATE", "98.50",
          "QUALITY_ACCIDENT", "100",
          "IQC_ON_TIME_RATE", "96.00",
          "QMS_AUDIT_SCORE", "92");
    }

    private Map<Long, BigDecimal> values(String dimension, String... codeValuePairs) {
      Map<Long, BigDecimal> values = new LinkedHashMap<>();
      for (int i = 0; i < codeValuePairs.length; i += 2) {
        values.put(indicatorId(dimension, codeValuePairs[i]), new BigDecimal(codeValuePairs[i + 1]));
      }
      return values;
    }

    Map<String, BigDecimal> storedScores(long sheetId) {
      Map<String, BigDecimal> byCode = new LinkedHashMap<>();
      jdbcTemplate
          .queryForList(
              "select i.code, sc.score_value from score sc join indicator i on i.id = sc.indicator_id"
                  + " where sc.sheet_id = ?",
              sheetId)
          .forEach(row -> byCode.put((String) row.get("code"), (BigDecimal) row.get("score_value")));
      return byCode;
    }

    String sourceOf(long sheetId, String code) {
      return jdbcTemplate.queryForObject(
          "select sc.source from score sc join indicator i on i.id = sc.indicator_id"
              + " where sc.sheet_id = ? and i.code = ?",
          String.class,
          sheetId,
          code);
    }

    String importBatchOf(long sheetId, String code) {
      return jdbcTemplate.queryForObject(
          "select sc.import_batch from score sc join indicator i on i.id = sc.indicator_id"
              + " where sc.sheet_id = ? and i.code = ?",
          String.class,
          sheetId,
          code);
    }

    long scoreRowCount(long taskId) {
      Long count =
          jdbcTemplate.queryForObject(
              "select count(*) from score sc join sheet s on s.id = sc.sheet_id"
                  + " join task_supplier ts on ts.id = s.task_supplier_id where ts.task_id = ?",
              Long.class,
              taskId);
      return count == null ? -1 : count;
    }

    String sheetStatus(long sheetId) {
      return jdbcTemplate.queryForObject("select status from sheet where id = ?", String.class, sheetId);
    }

    String taskSupplierStatus() {
      return jdbcTemplate.queryForObject(
          "select status from task_supplier where id = ?", String.class, taskSupplierId);
    }

    Integer taskSupplierCurrentNode() {
      return jdbcTemplate.queryForObject(
          "select current_node from task_supplier where id = ?", Integer.class, taskSupplierId);
    }

    int taskSupplierVersion() {
      Integer version =
          jdbcTemplate.queryForObject(
              "select version from task_supplier where id = ?", Integer.class, taskSupplierId);
      return version == null ? -1 : version;
    }
  }

  private Fixture publishedTask() {
    int year = PERIOD_SEQ.getAndIncrement();
    String suffix = String.valueOf(year);
    long supplier = insertSupplier("W4-" + suffix);
    long approver = insertUser("w4-approver-" + suffix, "MANAGER", "OTHER");
    long qualityEvaluator = insertUser("w4-eval-" + suffix, "EVALUATOR", "QUALITY");
    long fillRateEvaluator = insertUser("w4-eval2-" + suffix, "EVALUATOR", "DELIVERY");
    long costEvaluator = insertUser("w4-eval3-" + suffix, "EVALUATOR", "PURCHASE");
    long serviceEvaluator = insertUser("w4-eval4-" + suffix, "EVALUATOR", "SERVICE");
    Long versionId =
        jdbcTemplate.queryForObject(
            "select v.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'",
            Long.class);
    Long templateId =
        jdbcTemplate.queryForObject(
            "select t.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'",
            Long.class);
    long taskId =
        taskService.createDraft(
            new TaskDraftCommand(
                "W4 任务-" + suffix,
                year,
                1,
                versionId == null ? -1 : templateId,
                versionId == null ? -1 : versionId,
                LocalDate.of(year, 12, 31),
                List.of(supplier),
                List.of(approver)));
    taskService.publish(taskId, 0);

    Long taskSupplierId =
        jdbcTemplate.queryForObject(
            "select id from task_supplier where task_id = ?", Long.class, taskId);
    // 把四张评分单全部改派给同一个评估人：用例要能逐维度写/提交而不必切换身份
    long operator = qualityEvaluator;
    jdbcTemplate.update(
        "update sheet s join task_supplier ts on ts.id = s.task_supplier_id"
            + " set s.assignee_id = ?, s.status = 'SCORING' where ts.task_id = ?",
        operator,
        taskId);
    assertThat(List.of(fillRateEvaluator, costEvaluator, serviceEvaluator))
        .as("另外三个部门各有一名评估人，保证发布时四个维度都指派成功（本用例随后会把它们统一改派给 operator）")
        .hasSize(3);
    return new Fixture(year, taskId, taskSupplierId == null ? -1 : taskSupplierId, operator, costEvaluator);
  }

  private long insertSupplier(String code) {
    jdbcTemplate.update(
        "insert into supplier (code, name, category, status, version) values (?, ?, 'W4 测试', 'ENABLED', 0)",
        code,
        "供应商 " + code);
    Long id = jdbcTemplate.queryForObject("select id from supplier where code = ?", Long.class, code);
    return id == null ? -1 : id;
  }

  private long insertUser(String username, String role, String department) {
    jdbcTemplate.update(
        "insert into sys_user (username, password_hash, name, role, department, status, version)"
            + " values (?, '$2a$10$abcdefghijklmnopqrstuv', ?, ?, ?, 'ENABLED', 0)",
        username,
        "测试用户 " + username,
        role,
        department);
    Long id = jdbcTemplate.queryForObject("select id from sys_user where username = ?", Long.class, username);
    return id == null ? -1 : id;
  }
}
