package com.spe.context.evaluation.internal.task;

import com.spe.integration.support.TestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.common.page.PageQuery;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.events.TaskPublishedEvent;
import com.spe.context.evaluation.internal.sheet.MySheetView;
import com.spe.context.evaluation.internal.sheet.Sheet;
import com.spe.integration.AbstractIntegrationTest;
import com.spe.integration.support.FailOnNthStatementInterceptor;
import com.spe.integration.support.LockWaitProbe;
import com.spe.integration.support.ParkAfterStatementInterceptor;
import com.spe.integration.support.StatementGatesConfig;
import com.spe.integration.support.StatementRecorder;
import com.spe.context.template.internal.TemplateService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * W3 任务域的集成用例（真库 + 真事务）：创建/编辑草稿（W3.1）、发布 T1（W3.2）、自动指派（W3.3）、
 * 改派与可见性（W3.4）。
 *
 * <p>每条用例用**自己的周期**（年份递增）与**自己的供应商编码**：J4 是按"供应商 + 周期"判重的，
 * 共享同一个周期会让用例之间互相干扰（P1 的教训：共享库上不要断言全局状态）。
 */
@Import(StatementGatesConfig.class)
@RecordApplicationEvents
class TaskDomainIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicInteger PERIOD_SEQ = new AtomicInteger(2031);

  /**
   * 闸门只拦**这个前缀**的线程（见 {@code ParkAfterStatementInterceptor}）：主线程自己也要发布来造前置
   * 数据，只按语句 id 拦会把主线程一起停住、直接锁死用例。
   */
  private static final String WORKER = "spe-task-worker-";

  @Autowired private TaskService taskService;
  @Autowired private TemplateService templateService;

  @Autowired private StatementRecorder statements;

  @Autowired private ApplicationEvents applicationEvents;

  /** 语句计数与两个闸门都是**上下文级单例**，用例之间必须各自清干净，否则上一条用例的计数会串进来。 */
  @AfterEach
  void releaseTestGates() {
    ParkAfterStatementInterceptor.disarm();
    FailOnNthStatementInterceptor.disarm();
    statements.reset();
  }

  // ---------- W3.1 创建与编辑草稿 ----------

  @Test
  @DisplayName("W3.1 创建草稿：任务/供应商勾选/审批链三处落库，seq 从 1 起且顺序即审批顺序")
  void createDraftPersistsSelectionAndChain() {
    int year = nextYear();
    long supplierA = insertSupplier("W3-A-" + year);
    long supplierB = insertSupplier("W3-B-" + year);
    long approver1 = insertUser("w3-approver1-" + year, "MANAGER", "OTHER");
    long approver2 = insertUser("w3-approver2-" + year, "ADMIN", "OTHER");

    long taskId =
        taskService.createDraft(
            new TaskDraftCommand(
                " 2026 Q4 供应商评估 ",
                year,
                1,
                publishedDefaultTemplateId(),
                publishedDefaultVersionId(),
                LocalDate.of(year, 12, 31),
                List.of(supplierA, supplierB, supplierA),
                List.of(approver2, approver1)));

    Map<String, Object> task = taskRow(taskId);
    assertThat(task.get("status")).isEqualTo("DRAFT");
    assertThat(task.get("version")).isEqualTo(0);
    assertThat(task.get("name")).as("名称两端空白被去掉").isEqualTo("2026 Q4 供应商评估");
    assertThat(supplierIdsOf(taskId)).as("重复勾选被去重").containsExactly(supplierA, supplierB);
    assertThat(approverIdsOf(taskId)).as("审批顺序按入参顺序").containsExactly(approver2, approver1);
    assertThat(seqOf(taskId)).as("seq 从 1 起连续").containsExactly(1, 2);
  }

  @Test
  @DisplayName("W3.1 编辑草稿：版本对不上给 3001；已发布的任务再编辑给 2020（E20 的两分口径）")
  void editDraftDistinguishesConflictFromIllegalStatus() {
    int year = nextYear();
    long supplier = insertSupplier("W3-EDIT-" + year);
    long approver = insertUser("w3-edit-approver-" + year, "MANAGER", "OTHER");
    long taskId = createDraft(year, List.of(supplier), List.of(approver));

    TaskDraftCommand renamed = command(year, "改名后的任务", List.of(supplier), List.of(approver));
    assertThatThrownBy(() -> taskService.updateDraft(taskId, 99, renamed))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);

    taskService.updateDraft(taskId, 0, renamed);
    assertThat(taskRow(taskId).get("name")).isEqualTo("改名后的任务");
    assertThat(taskRow(taskId).get("version")).as("守卫 UPDATE 自增 version").isEqualTo(1);

    taskService.publish(taskId, 1);
    int versionAfterPublish = ((Number) taskRow(taskId).get("version")).intValue();
    assertThat(versionAfterPublish).as("发布后 version 再自增一次").isEqualTo(2);
    assertThatThrownBy(() -> taskService.updateDraft(taskId, versionAfterPublish, renamed))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .as("已发布的任务不能再当草稿改：版本是对的，拦住它的是**状态** ⇒ 2020（不是 3001）")
        .isEqualTo(ErrorCode.TASK_STATUS_INVALID);
  }

  @Test
  @DisplayName("W6 审批链角色口径（P1-16 ③）：EVALUATOR 进不了审批链；历史脏数据在发布时也会被复核拦住")
  void approvalChainOnlyAcceptsManagerOrAdmin() {
    int year = nextYear();
    long supplier = insertSupplier("W6-ROLE-" + year);
    long evaluator = insertUser("w6-role-eval-" + year, "EVALUATOR", "QUALITY");
    long manager = insertUser("w6-role-mgr-" + year, "MANAGER", "PURCHASE");

    assertThatThrownBy(
            () ->
                taskService.createDraft(
                    new TaskDraftCommand(
                        "角色口径-" + year,
                        year,
                        1,
                        publishedDefaultTemplateId(),
                        publishedDefaultVersionId(),
                        LocalDate.of(year, 12, 31),
                        List.of(supplier),
                        List.of(evaluator))))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.USER_NOT_ELIGIBLE);

    long taskId = createDraft(year, List.of(supplier), List.of(manager));
    // 绕过服务层直接改库（模拟口径收窄之前已发布/已保存的脏链）⇒ 发布时的复核必须拦住
    jdbcTemplate.update("update approval_node set approver_id = ? where task_id = ?", evaluator, taskId);
    assertThatThrownBy(() -> taskService.publish(taskId, 0))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("审批人必须是采购经理或管理员")
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.USER_NOT_ELIGIBLE);
    assertThat(taskRow(taskId).get("status")).as("发布失败必须留在草稿").isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("W6 模板归属校验（P1-17）：版本不属于所选模板 ⇒ 2031，且不落任务行")
  void templateVersionMustBelongToTheClaimedTemplate() {
    int year = nextYear();
    long supplier = insertSupplier("W6-TPL-" + year);
    long approver = insertUser("w6-tpl-approver-" + year, "MANAGER", "OTHER");
    long otherTemplateId = templateService.create("W6 另一模板-" + year).id();

    String name = "串模板任务-" + year;
    assertThatThrownBy(
            () ->
                taskService.createDraft(
                    new TaskDraftCommand(
                        name,
                        year,
                        1,
                        otherTemplateId,
                        publishedDefaultVersionId(),
                        LocalDate.of(year, 12, 31),
                        List.of(supplier),
                        List.of(approver))))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TEMPLATE_VERSION_MISMATCH);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from eval_task where name = ?", Long.class, name))
        .as("失败路径不许留下任务行")
        .isZero();

    // 同一份命令只把 templateId 改回真实归属 ⇒ 通过
    long taskId =
        taskService.createDraft(
            new TaskDraftCommand(
                name,
                year,
                1,
                publishedDefaultTemplateId(),
                publishedDefaultVersionId(),
                LocalDate.of(year, 12, 31),
                List.of(supplier),
                List.of(approver)));
    assertThat(taskId).isPositive();
  }

  @Test
  @DisplayName("W6 关键字按字面匹配（P2-37）：% 与 _ 不再是通配符")
  void keywordSearchTreatsWildcardsLiterally() {
    int year = nextYear();
    long approver = insertUser("w6-like-approver-" + year, "MANAGER", "OTHER");
    long supplierA = insertSupplier("W6-LIKE-A-" + year);
    long supplierB = insertSupplier("W6-LIKE-B-" + year);
    long supplierC = insertSupplier("W6-LIKE-C-" + year);
    long supplierD = insertSupplier("W6-LIKE-D-" + year);

    taskService.createDraft(
        command(year, "W6 100% 达成-" + year, List.of(supplierA), List.of(approver)));
    taskService.createDraft(
        command(year, "W6 1000 达成-" + year, List.of(supplierB), List.of(approver)));
    taskService.createDraft(
        command(year, "W6 A_B 任务-" + year, List.of(supplierC), List.of(approver)));
    taskService.createDraft(
        command(year, "W6 AXB 任务-" + year, List.of(supplierD), List.of(approver)));

    // 未转义时 % 会同时命中 "1000 达成"；转义后只命中字面含 % 的那条
    assertThat(
            taskService.page(PageQuery.of(1, 20), null, "100% 达成-" + year).list())
        .extracting(TaskSummary::name)
        .containsExactly("W6 100% 达成-" + year);

    // 未转义时 _ 会同时命中 "AXB 任务"；转义后只命中字面含 _ 的那条
    assertThat(taskService.page(PageQuery.of(1, 20), null, "A_B 任务-" + year).list())
        .extracting(TaskSummary::name)
        .containsExactly("W6 A_B 任务-" + year);
  }

  // ---------- W3.2 发布 ----------

  @Test
  @DisplayName("W3.2 发布 T1：评估单 = 供应商数、评分单 = 供应商数 × 维度数，任务转进行中并留痕")
  void publishGeneratesTaskSuppliersSheetsAndAudit() {
    int year = nextYear();
    long supplierA = insertSupplier("W3-PUB-A-" + year);
    long supplierB = insertSupplier("W3-PUB-B-" + year);
    long approver = insertUser("w3-pub-approver-" + year, "MANAGER", "OTHER");
    long taskId = createDraft(year, List.of(supplierA, supplierB), List.of(approver));
    long dimensionCount = dimensionCount(publishedDefaultVersionId());

    PublishResult result = taskService.publish(taskId, 0);

    assertThat(result.supplierCount()).as("评估单数 = 勾选的供应商数").isEqualTo(2);
    assertThat(result.sheetCount()).as("评分单数 = 供应商数 × 维度数").isEqualTo((int) (2 * dimensionCount));
    assertThat(result.sheetCount()).as("数出来的东西要断言 > 0").isGreaterThan(0);
    assertThat(taskSupplierCount(taskId)).isEqualTo(2);
    assertThat(sheetCount(taskId)).isEqualTo(2 * dimensionCount);
    assertThat(taskRow(taskId).get("status")).isEqualTo("IN_PROGRESS");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'TASK_PUBLISH' and target_id = ?",
                Long.class,
                taskId))
        .as("发布必须留痕（06 §2.8）")
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("W3.2 重复发布：状态守卫拦住（2020），且不产生第二套评分单")
  void repeatPublishIsRejectedWithoutDuplicatingSheets() {
    int year = nextYear();
    long supplier = insertSupplier("W3-REPEAT-" + year);
    long approver = insertUser("w3-repeat-approver-" + year, "MANAGER", "OTHER");
    long taskId = createDraft(year, List.of(supplier), List.of(approver));
    taskService.publish(taskId, 0);
    long sheetsAfterFirst = sheetCount(taskId);

    assertThatThrownBy(() -> taskService.publish(taskId, 1))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TASK_STATUS_INVALID);
    assertThat(sheetCount(taskId)).as("重复发布不能产生第二套评分单").isEqualTo(sheetsAfterFirst);
  }

  @Test
  @DisplayName("W3.2 并发发布：恰好一方成功、另一方被状态守卫或唯一键拦住，评分单不翻倍")
  void concurrentPublishExactlyOneWins() throws Exception {
    int year = nextYear();
    long supplier = insertSupplier("W3-CONC-" + year);
    long approver = insertUser("w3-conc-approver-" + year, "MANAGER", "OTHER");
    long taskId = createDraft(year, List.of(supplier), List.of(approver));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<String> publish =
        () -> {
          start.await();
          try {
            taskService.publish(taskId, 0);
            return "OK";
          } catch (BizException ex) {
            return ex.errorCode().name();
          }
        };
    Future<String> first = pool.submit(publish);
    Future<String> second = pool.submit(publish);
    start.countDown();
    List<String> results =
        List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
    pool.shutdown();

    assertThat(results)
        .as("恰好一方成功，另一方是**明确业务码**（状态守卫 2020 或唯一键 3001），不是 5xxx")
        .containsExactlyInAnyOrder("OK", ErrorCode.TASK_STATUS_INVALID.name());
    assertThat(sheetCount(taskId)).as("评分单只能是单份").isEqualTo(dimensionCount(publishedDefaultVersionId()));
  }

  @Test
  @DisplayName("W3.2 发布中途失败 ⇒ 整体回滚：预置一张冲突的评分单，发布抛 3001 且任务仍是草稿")
  void publishFailureRollsBackEverything() {
    int year = nextYear();
    long supplier = insertSupplier("W3-ROLLBACK-" + year);
    long approver = insertUser("w3-rollback-approver-" + year, "MANAGER", "OTHER");
    long taskId = createDraft(year, List.of(supplier), List.of(approver));
    long taskSupplierId = supplierIdsOf(taskId).isEmpty() ? -1 : taskSupplierRowId(taskId);
    long dimensionId = firstDimensionId(publishedDefaultVersionId());
    // 预置一张评分单，让发布时的那条 INSERT 撞 uk_sheet
    jdbcTemplate.update(
        "insert into sheet (task_supplier_id, dimension_id, assignee_id, status, version) values (?, ?, null, 'UNASSIGNED', 0)",
        taskSupplierId,
        dimensionId);

    assertThatThrownBy(() -> taskService.publish(taskId, 0))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .as("唯一键冲突要翻成明确业务码（13 §E10），不能漏成 5xxx")
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    assertThat(sheetCount(taskId)).as("发布整体回滚：只剩预置的那一张").isEqualTo(1);
    assertThat(taskRow(taskId).get("status")).as("任务没有进入进行中").isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("W3.2 J4：同一供应商同一周期已有非取消任务 ⇒ 第二个任务发布被拒（2019）")
  void j4RejectsSecondTaskWithSameSupplierInPeriod() {
    int year = nextYear();
    long supplier = insertSupplier("W3-J4-" + year);
    long approver = insertUser("w3-j4-approver-" + year, "MANAGER", "OTHER");
    long first = createDraft(year, List.of(supplier), List.of(approver));
    taskService.publish(first, 0);

    long second = createDraft(year, List.of(supplier), List.of(approver));
    assertThatThrownBy(() -> taskService.publish(second, 0))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.TASK_PERIOD_DUPLICATE);
    assertThat(taskRow(second).get("status")).as("失败的任务留在草稿").isEqualTo("DRAFT");
    assertThat(sheetCount(second)).as("没有生成任何评分单").isZero();
  }

  /**
   * P1-15 的根治判据（03 §38；执行的是 13 §E10 §R1 预留的升级触发条件"实测能复现 ⇒ 改周期锁"）。
   *
   * <p>要证的不是"结果碰巧对"，而是<b>第二个发布被挡在数据库的锁上、而不是和第一个互相死锁</b>：
   * 让 A 停在"整表 X 锁已经到手"之后，用 {@link LockWaitProbe} 亲眼看到 B 的连接停在
   * {@code ... from eval_task ... for update} 上，才认定并发场景真的复现了（看不到就抛错，
   * 绝不退化成"串行跑完假装通过"）。
   *
   * <p>结果为什么是<b>两个都 2019</b>：J4 判的兄弟集合含草稿（{@code status <> 'CANCELED'} 没排除
   * DRAFT，这是 01 J4 的字面含义），所以两个勾了同一批供应商的草稿互相都把对方当成冲突。
   * 关键判据是那条 {@code doesNotContain(CONCURRENT_CONFLICT)}——改动前这里是随机出现
   * {@code DeadlockLoserDataAccessException}（被 advice 翻成 3001）的。
   */
  @Test
  @DisplayName("P1-15 根治：同周期的两个发布在 eval_task 的 X 锁上排队，不再互相死锁")
  void publishesInSamePeriodQueueInsteadOfDeadlocking() throws Exception {
    int year = nextYear();
    long supplier = insertSupplier("W3-J4LOCK-" + year);
    long approver = insertUser("w3-j4lock-approver-" + year, "MANAGER", "OTHER");
    long first = createDraft(year, List.of(supplier), List.of(approver));
    long second = createDraft(year, List.of(supplier), List.of(approver));

    ExecutorService pool = namedPool(2);
    try {
      ParkAfterStatementInterceptor.Gate gate =
          ParkAfterStatementInterceptor.arm("EvalTaskMapper.lockAllTasksByIdAsc", WORKER);
      Future<String> a = pool.submit(() -> publishQuietly(first));
      gate.awaitParked();

      Future<String> b = pool.submit(() -> publishQuietly(second));
      String blocked = lockWaits().awaitBlockedStatement(Duration.ofSeconds(15));
      assertThat(blocked)
          .as("第二个发布必须被挡在 eval_task 的升序 FOR UPDATE 上（实际看到的语句：%s）", blocked)
          .contains("from eval_task")
          .contains("for update");

      gate.resume();
      List<String> results = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));

      assertThat(results)
          .as("两个草稿互相冲突 ⇒ 谁都不能发布（实际=%s）", results)
          .containsExactlyInAnyOrder(
              ErrorCode.TASK_PERIOD_DUPLICATE.name(), ErrorCode.TASK_PERIOD_DUPLICATE.name());
      assertThat(results)
          .as("不得再出现死锁/锁等待翻出来的 3001（实际=%s）", results)
          .doesNotContain(ErrorCode.CONCURRENT_CONFLICT.name());
      for (long taskId : List.of(first, second)) {
        assertThat(taskRow(taskId).get("status")).as("taskId=%s 必须留在草稿", taskId).isEqualTo("DRAFT");
        assertThat(taskRow(taskId).get("version")).as("taskId=%s 版本未被改动", taskId).isEqualTo(0);
        assertThat(sheetCount(taskId)).as("taskId=%s 不留半截评分单", taskId).isZero();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 同上的场景，但**不注入闸门**、连打 20 轮：死锁是小概率交错，所以判据写成"20 轮里 0 次 3001"。
   * 这是统计性的防回归（次数是判据，延迟不是），补上面那条"确定交错"的用例。
   */
  @Test
  @DisplayName("P1-15 压测：同周期同供应商连打 20 轮并发发布，3001 出现 0 次")
  void twentyRoundsOfConcurrentPublishNeverSurfaceALockConflict() throws Exception {
    int year = nextYear();
    long supplier = insertSupplier("W3-J4ROUNDS-" + year);
    long approver = insertUser("w3-j4rounds-approver-" + year, "MANAGER", "OTHER");
    long first = createDraft(year, List.of(supplier), List.of(approver));
    long second = createDraft(year, List.of(supplier), List.of(approver));

    ExecutorService pool = namedPool(2);
    try {
      for (int round = 1; round <= 20; round++) {
        CountDownLatch start = new CountDownLatch(1);
        Future<String> a = pool.submit(() -> publishAfter(start, first));
        Future<String> b = pool.submit(() -> publishAfter(start, second));
        start.countDown();
        List<String> results = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        assertThat(results)
            .as("第 %s 轮：两个结果都必须是明确业务码，且不得出现死锁翻译出来的 3001（实际=%s）", round, results)
            .containsOnly(ErrorCode.TASK_PERIOD_DUPLICATE.name());
      }
      assertThat(taskRow(first).get("status")).isEqualTo("DRAFT");
      assertThat(taskRow(second).get("status")).isEqualTo("DRAFT");
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 根治的**反面**判据：把互斥加过头会把合法并发也一起挡掉。同周期、供应商不相交的两个任务
   * 并发发布必须都成功，且各自生成自己那批评分单。
   */
  @Test
  @DisplayName("同周期但供应商不相交的两个任务并发发布：两个都成功（根治没杀掉合法并发）")
  void disjointSuppliersInSamePeriodBothPublish() throws Exception {
    int year = nextYear();
    long supplierA = insertSupplier("W3-J4OK-A-" + year);
    long supplierB = insertSupplier("W3-J4OK-B-" + year);
    long approver = insertUser("w3-j4ok-approver-" + year, "MANAGER", "OTHER");
    long first = createDraft(year, List.of(supplierA), List.of(approver));
    long second = createDraft(year, List.of(supplierB), List.of(approver));
    long dimensions = dimensionCount(publishedDefaultVersionId());

    ExecutorService pool = namedPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Future<String> a = pool.submit(() -> publishAfter(start, first));
      Future<String> b = pool.submit(() -> publishAfter(start, second));
      start.countDown();
      List<String> results = List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));

      assertThat(results).as("供应商不相交 ⇒ 两个都该发布成功（实际=%s）", results).containsOnly("OK");
      assertThat(taskRow(first).get("status")).isEqualTo("IN_PROGRESS");
      assertThat(taskRow(second).get("status")).isEqualTo("IN_PROGRESS");
      assertThat(sheetCount(first)).as("各自的评分单数 = 供应商数 × 维度数").isEqualTo(dimensions);
      assertThat(sheetCount(second)).isEqualTo(dimensions);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 兄弟集合的过滤条件从 SQL 搬进了 Java（{@code siblingTaskIdsInPeriod}），这条用例钉住它的
   * {@code status <> 'CANCELED'} 那一半：删掉这个条件，这里就会变成 2019 而红。
   */
  @Test
  @DisplayName("兄弟任务已取消 ⇒ 不挡发布（Java 过滤与旧 SQL 的 status <> 'CANCELED' 同语义）")
  void canceledSiblingDoesNotBlockPublish() {
    int year = nextYear();
    long supplier = insertSupplier("W3-J4CANCEL-" + year);
    long approver = insertUser("w3-j4cancel-approver-" + year, "MANAGER", "OTHER");
    long blocker = createDraft(year, List.of(supplier), List.of(approver));
    long target = createDraft(year, List.of(supplier), List.of(approver));
    // CANCELED 是"枚举预留、入口后置"（13 §E3），没有业务代码会写它 ⇒ 只能按库表现实地造一个
    jdbcTemplate.update("update eval_task set status = 'CANCELED' where id = ?", blocker);

    taskService.publish(target, 0);

    assertThat(taskRow(target).get("status")).isEqualTo("IN_PROGRESS");
    assertThat(sheetCount(target)).isEqualTo(dimensionCount(publishedDefaultVersionId()));
  }

  /**
   * 本轮登记的新代价：这条全表扫描留下的 next-key 锁会在发布期间挡住新建任务的 INSERT ⇒
   * "发布进行中 ⇒ 新建草稿排队"。排队本身不是问题（实测 S 与 X 都会挡，这条账本来就在付），
   * <b>把等待者翻成 3001 才是问题</b>——这条用例钉的就是后半句：放行后草稿照常落成 DRAFT、版本 0。
   * 上限是 {@code innodb_lock_wait_timeout}（实测默认 50s），演示规模的一次发布远小于它。
   */
  @Test
  @DisplayName("发布持锁期间新建草稿：停在插入上排队，放行后正常落库、不是 3001")
  void draftCreationWaitsInsteadOfFailingWhilePublishHoldsTheLock() throws Exception {
    int year = nextYear();
    int otherYear = nextYear();
    long supplier = insertSupplier("W3-GAP-" + year);
    long approver = insertUser("w3-gap-approver-" + year, "MANAGER", "OTHER");
    long taskId = createDraft(year, List.of(supplier), List.of(approver));
    long versionId = publishedDefaultVersionId();

    ExecutorService pool = namedPool(2);
    try {
      ParkAfterStatementInterceptor.Gate gate =
          ParkAfterStatementInterceptor.arm("EvalTaskMapper.lockAllTasksByIdAsc", WORKER);
      Future<String> publishing = pool.submit(() -> publishQuietly(taskId));
      gate.awaitParked();

      Future<Long> creating =
          pool.submit(
              () ->
                  taskService.createDraft(
                      new TaskDraftCommand(
                          "排队中的草稿",
                          otherYear,
                          2,
                          publishedDefaultTemplateId(),
                          versionId,
                          LocalDate.of(otherYear, 12, 31),
                          List.of(supplier),
                          List.of(approver))));
      String blocked = lockWaits().awaitBlockedStatement(Duration.ofSeconds(15));
      assertThat(blocked)
          .as("新建草稿必须停在 eval_task 的插入上（实际：%s）", blocked)
          .contains("insert into eval_task");

      gate.resume();
      long draftId = creating.get(60, TimeUnit.SECONDS);
      assertThat(publishing.get(60, TimeUnit.SECONDS)).isEqualTo("OK");

      assertThat(taskRow(draftId).get("status")).as("排队不等于失败").isEqualTo("DRAFT");
      assertThat(taskRow(draftId).get("version")).isEqualTo(0);
      assertThat(taskRow(taskId).get("status")).isEqualTo("IN_PROGRESS");
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * CLAUDE 默认约定第 6 条（"锁定读要是这个事务的第一条语句"）唯一能机械化的守卫：
   * 直接数发布事务里**第一条被执行的语句**。它红了就说明有人在前面插了一条普通读——
   * 那正是 P1-1/P1-2 与 04-056/057/076/096 反复付过学费的那个形态。
   */
  @Test
  @DisplayName("发布事务的第一条语句必须是 eval_task 的升序 FOR UPDATE")
  void publishTakesTheTableLockAsItsFirstStatement() {
    int year = nextYear();
    long supplier = insertSupplier("W3-ORDER-" + year);
    long approver = insertUser("w3-order-approver-" + year, "MANAGER", "OTHER");
    long taskId = createDraft(year, List.of(supplier), List.of(approver));

    statements.reset();
    taskService.publish(taskId, 0);

    assertThat(statements.executedStatements())
        .as("第一条语句若不是那道整表 FOR UPDATE，J4 的当前读语义与 P1-15 的串行化同时失效")
        .startsWith("com.spe.context.evaluation.internal.task.EvalTaskMapper.lockAllTasksByIdAsc");
    assertThat(statements.countOf("EvalTaskMapper.lockAllTasksByIdAsc"))
        .as("一次发布只取一次表级锁")
        .isEqualTo(1);
  }

  // ---------- P1-9：集合入参上限与批量分片 ----------

  /**
   * 01 的规模上限（500 家供应商）本身就是**合法输入**，所以"一次发布 2000 行"不是理论风险。
   * 判据按表数批次：{@code sheet} = 2000 行 ⇒ 4 批、{@code task_supplier} = 500 行 ⇒ 1 批、
   * {@code approval_node} ≤ 10 行 ⇒ 1 批。数的是**语句次数**（06 §8 第 17 条），不是延迟。
   *
   * <p>分片后 {@code useGeneratedKeys} 的回填由事件载荷代验：{@code SheetAssignment.sheetId} 是
   * {@code long}，哪一批没回填上就会在构造事件时 NPE 或留下重复的 0，所以断言"2000 个互不相同"。
   */
  @Test
  @DisplayName("P1-9 规模：500 供应商 × 4 维度发布 ⇒ 按表数得到 4/1/1 批，且每行都拿到 id")
  void publishOfFiveHundredSuppliersSplitsIntoCountableBatches() {
    int year = nextYear();
    String codePrefix = "W3-500-" + year;
    long approver = insertUser("w3-500-approver-" + year, "MANAGER", "OTHER");
    List<Long> suppliers = insertSuppliers(500, codePrefix);
    long dimensions = dimensionCount(publishedDefaultVersionId());
    assertThat(dimensions).as("V8 的默认模板应有 4 个维度").isEqualTo(4);

    statements.reset();
    long taskId = createDraft(year, suppliers, List.of(approver));
    try {
      assertThat(statements.countOf("TaskSupplierMapper.insertBatch"))
          .as("500 家 = 正好一批")
          .isEqualTo(1);
      assertThat(statements.countOf("ApprovalNodeMapper.insertBatch")).as("审批链 1 级 = 一批").isEqualTo(1);

      statements.reset();
      PublishResult result = taskService.publish(taskId, 0);

      assertThat(statements.countOf("SheetMapper.insertBatch"))
          .as("500 × 4 = 2000 行，每批最多 500 ⇒ 必须是 4 批")
          .isEqualTo(4);
      assertThat(result.supplierCount()).isEqualTo(500);
      assertThat(result.sheetCount()).isEqualTo(500 * dimensions);
      assertThat(taskSupplierCount(taskId)).isEqualTo(500);
      assertThat(sheetCount(taskId)).isEqualTo(500 * dimensions);

      List<Long> sheetIds =
          applicationEvents
              .stream(TaskPublishedEvent.class)
              .filter(event -> event.taskId() == taskId)
              .findFirst()
              .orElseThrow(() -> new AssertionError("发布必须发出 TaskPublishedEvent"))
              .assignments()
              .stream()
              .map(TaskPublishedEvent.SheetAssignment::sheetId)
              .toList();
      assertThat(sheetIds)
          .as("分 4 批插入后每行的 id 都要回填上（事件载荷用的是对象里的 id）")
          .hasSize(500 * (int) dimensions)
          .doesNotHaveDuplicates();
    } finally {
      purgeTaskFixture(taskId, codePrefix);
    }
  }

  /**
   * 分片的**风险控制**：批与批之间必须还是同一个事务。做法是在第 2 批上抛一个与业务无关的
   * {@code IllegalStateException}——不能用唯一键冲突，那条会被发布路径自己的 try/catch 翻成 3001，
   * 就测不到"回滚"了。判据是"第 1 批已落的 500 行也一起消失、任务留在草稿、没有发布留痕"。
   */
  @Test
  @DisplayName("P1-9 回滚：第 2 批插入抛错 ⇒ 第 1 批与任务状态、审计一起回滚")
  void secondBatchFailureRollsBackTheWholePublish() {
    int year = nextYear();
    String codePrefix = "W3-ROLL-" + year;
    long approver = insertUser("w3-rollback-approver-" + year, "MANAGER", "OTHER");
    long dimensions = dimensionCount(publishedDefaultVersionId());
    List<Long> suppliers = insertSuppliers(130, codePrefix);
    long rows = suppliers.size() * dimensions;
    assertThat(rows).as("必须正好跨到第 2 批（501~1000 行）才测得到回滚").isBetween(501L, 1000L);
    long taskId = createDraft(year, suppliers, List.of(approver));
    try {
      statements.reset();
      FailOnNthStatementInterceptor.arm("SheetMapper.insertBatch", 2);
      assertThatThrownBy(() -> taskService.publish(taskId, 0))
          .as("引爆点必须正好是第 2 批（不是第 1 批，也不是闸门根本没触发）")
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("第 2 次 SheetMapper.insertBatch");

      assertThat(statements.countOf("SheetMapper.insertBatch"))
          .as("失败前只有第 1 批真的进了库 ⇒ \"已落的 500 行也一起回滚\"这件事才有内容")
          .isEqualTo(1);
      Map<String, Object> task = taskRow(taskId);
      assertThat(task.get("status")).as("任务必须留在草稿").isEqualTo("DRAFT");
      assertThat(task.get("version")).as("守卫 UPDATE 没执行过").isEqualTo(0);
      assertThat(sheetCount(taskId)).as("第 1 批已执行的 500 行必须一起回滚").isZero();
      assertThat(
              auditRows("TASK_PUBLISH").stream()
                  .filter(row -> ((Number) row.get("target_id")).longValue() == taskId)
                  .count())
            .as("审计与发布同事务：没发布成功就不该留下发布留痕")
          .isZero();
    } finally {
      purgeTaskFixture(taskId, codePrefix);
    }
  }

  // ---------- 并发/分片用例的工具 ----------

  /** 与 HTTP 入口**同一口径**记账：advice 把锁等待/死锁集中翻成 3001（{@code GlobalExceptionHandler:60-65}）。 */
  private String publishQuietly(long taskId) {
    try {
      taskService.publish(taskId, 0);
      return "OK";
    } catch (BizException ex) {
      return ex.errorCode().name();
    } catch (PessimisticLockingFailureException lockConflict) {
      return ErrorCode.CONCURRENT_CONFLICT.name();
    }
  }

  private String publishAfter(CountDownLatch start, long taskId) throws InterruptedException {
    start.await();
    return publishQuietly(taskId);
  }

  /** 线程名带 {@link #WORKER} 前缀，闸门才认得出该拦谁。 */
  private static ExecutorService namedPool(int size) {
    return Executors.newFixedThreadPool(
        size, runnable -> new Thread(runnable, WORKER + THREAD_COUNTER.incrementAndGet()));
  }

  private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

  private LockWaitProbe lockWaits() {
    return new LockWaitProbe(jdbcTemplate);
  }

  /**
   * 把本用例造的整棵树删掉。**大数据量的用例必须自清理**：测试库是跨用例共享的
   * （`AbstractIntegrationTest` 刻意不加 `@Transactional`，好让"失败路径的审计不被回滚"这种事可测），
   * 而一次 500 家供应商的发布留下的是 2000 行 `sheet` —— 它会把后来者的 EXPLAIN 判据整体改掉：
   * `mySheetsQueryUsesAssigneeIndex` 正是被这条清理缺失搞红的（`assignee_id` 的区分度没了，
   * 优化器就不选 05 §8.2 为待办准备的那个索引）。
   */
  private void purgeTaskFixture(long taskId, String supplierCodePrefix) {
    jdbcTemplate.update(
        "delete sc from score sc"
            + " join sheet s on s.id = sc.sheet_id"
            + " join task_supplier ts on ts.id = s.task_supplier_id where ts.task_id = ?",
        taskId);
    jdbcTemplate.update(
        "delete s from sheet s join task_supplier ts on ts.id = s.task_supplier_id where ts.task_id = ?",
        taskId);
    jdbcTemplate.update("delete from task_supplier where task_id = ?", taskId);
    jdbcTemplate.update("delete from approval_node where task_id = ?", taskId);
    jdbcTemplate.update("delete from eval_task where id = ?", taskId);
    jdbcTemplate.update("delete from supplier where code like ?", supplierCodePrefix + "-%");
  }

  /** 一次造 N 家供应商（500 行用例与 130 行用例都要）；走 batchUpdate，否则是 2N 次 JDBC 往返。 */
  private List<Long> insertSuppliers(int count, String codePrefix) {
    List<Object[]> rows = new ArrayList<>(count);
    for (int i = 1; i <= count; i++) {
      String code = codePrefix + "-" + i;
      rows.add(new Object[] {code, "供应商 " + code});
    }
    jdbcTemplate.batchUpdate(
        "insert into supplier (code, name, category, status, version) values (?, ?, 'W3 测试', 'ENABLED', 0)",
        rows);
    List<Long> ids =
        jdbcTemplate.queryForList(
            "select id from supplier where code like ? order by id", Long.class, codePrefix + "-%");
    assertThat(ids).as("批量插入的供应商数").hasSize(count);
    return ids;
  }

  // ---------- W3.3 自动指派 ----------

  @Test
  @DisplayName("W3.3 自动指派：逐维度断言（质量→该部门评估人、交付物流→该部门经理、无人的部门→待指派）")
  void autoAssignFollowsSuggestedDepartmentPerDimension() {
    int year = nextYear();
    long supplier = insertSupplier("W3-ASSIGN-" + year);
    long approver = insertUser("w3-assign-approver-" + year, "MANAGER", "OTHER");
    insertUser("w3-quality-" + year, "EVALUATOR", "QUALITY");
    insertUser("w3-delivery-" + year, "MANAGER", "DELIVERY");
    // 断言的是"部门内 id 最小者"，所以期望值要**现查**——共享测试库里可能还有别的用例建的同部门用户
    long qualityEvaluator = smallestEnabledUserIdIn("QUALITY");
    long deliveryManager = smallestEnabledUserIdIn("DELIVERY");
    long taskId = createDraft(year, List.of(supplier), List.of(approver));

    PublishResult result = taskService.publish(taskId, 0);

    Map<String, Long> assigneeByDimension = new LinkedHashMap<>();
    jdbcTemplate
        .queryForList(
            "select d.name, s.assignee_id from sheet s join dimension d on d.id = s.dimension_id"
                + " join task_supplier ts on ts.id = s.task_supplier_id where ts.task_id = ?",
            taskId)
        .forEach(row -> assigneeByDimension.put((String) row.get("name"), (Long) row.get("assignee_id")));

    // 期望值**从库里现算**：正确结果 = 该建议部门下 id 最小的在职 EVALUATOR/MANAGER（没人则 null）。
    // 不能写死"采购/服务 没人"——共享测试库里别的用例也会建同部门的用户（04-092 的教训）
    Map<String, String> departmentByDimension =
        Map.of("质量", "QUALITY", "交付", "DELIVERY", "成本", "PURCHASE", "服务", "SERVICE");
    int expectedAssigned = 0;
    for (Map.Entry<String, String> entry : departmentByDimension.entrySet()) {
      Long expected = smallestEnabledUserIdOrNull(entry.getValue());
      assertThat(assigneeByDimension)
          .as("维度 %s 必须指派给 %s 部门 id 最小的在职评估人/经理（没人则 null）", entry.getKey(), entry.getValue())
          .containsEntry(entry.getKey(), expected);
      if (expected != null) {
        expectedAssigned++;
      }
    }
    assertThat(result.assignedCount()).as("指派成功数 = 有人的部门数").isEqualTo(expectedAssigned);
    assertThat(result.unassignedCount()).as("未指派数 = 没人的部门数").isEqualTo(4 - expectedAssigned);
    assertThat(qualityEvaluator).isNotNull();
    // 与上面同一口径：未指派数也**现算**（"采购/服务 一定没人"在共享库里不成立，04-092）
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from sheet s join task_supplier ts on ts.id = s.task_supplier_id"
                    + " where ts.task_id = ? and s.status = 'UNASSIGNED' and s.assignee_id is null",
                Long.class,
                taskId))
        .as("未指派的评分单数 = 没有可用评估人的维度数")
        .isEqualTo(4L - expectedAssigned);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from sheet s join task_supplier ts on ts.id = s.task_supplier_id"
                    + " where ts.task_id = ? and (s.status = 'UNASSIGNED') <> (s.assignee_id is null)",
                Long.class,
                taskId))
        .as("状态与指派对象必须一致：UNASSIGNED ⇔ assignee_id 为空")
        .isZero();
  }

  // ---------- W3.4 改派与可见性 ----------

  @Test
  @DisplayName("W3.4 改派：新负责人看得到、旧负责人看不到（4002），且评分单 id 与状态不被换掉")
  void reassignMovesVisibilityWithoutChangingTheSheet() {
    int year = nextYear();
    long supplier = insertSupplier("W3-REASSIGN-" + year);
    long approver = insertUser("w3-reassign-approver-" + year, "MANAGER", "OTHER");
    insertUser("w3-person-a-" + year, "EVALUATOR", "QUALITY");
    long personB = insertUser("w3-person-b-" + year, "EVALUATOR", "QUALITY");
    long taskId = createDraft(year, List.of(supplier), List.of(approver));
    taskService.publish(taskId, 0);
    long sheetId = sheetIdOfDimension(taskId, "质量");
    Long previousAssignee = (Long) sheetOf(sheetId).get("assignee_id");
    assertThat(previousAssignee).as("发布时按建议部门自动指派，一定有负责人").isNotNull();
    assertThat(mySheetIds(previousAssignee)).as("改派前旧负责人看得到").contains(sheetId);

    taskService.reassign(sheetId, personB, 0);

    assertThat(mySheetIds(personB)).as("改派后新负责人看得到").contains(sheetId);
    assertThat(mySheetIds(previousAssignee)).as("改派后旧负责人看不到").doesNotContain(sheetId);
    Sheet after = taskService.requireReadableSheet(sheetId, personB);
    assertThat(after.getId()).as("不换单").isEqualTo(sheetId);
    assertThat(after.getStatus()).isEqualTo(com.spe.context.evaluation.api.SheetStatus.SCORING);
    assertThatThrownBy(() -> taskService.requireReadableSheet(sheetId, previousAssignee))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .as("第二层过滤：横向越权给 4xxx")
        .isEqualTo(ErrorCode.SHEET_NOT_ACCESSIBLE);
  }

  @Test
  @DisplayName("W3.4 我的待办：查询走 idx_sheet_assignee_status（EXPLAIN 实测，不靠延迟）")
  void mySheetsQueryUsesAssigneeIndex() {
    int year = nextYear();
    long supplier = insertSupplier("W3-EXPLAIN-" + year);
    long approver = insertUser("w3-explain-approver-" + year, "MANAGER", "OTHER");
    long evaluator = insertUser("w3-explain-eval-" + year, "EVALUATOR", "QUALITY");
    long taskId = createDraft(year, List.of(supplier), List.of(approver));
    taskService.publish(taskId, 0);
    // 用**实际被指派的人**（自动指派取部门内 id 最小者，未必是本用例刚建的那个）再查待办
    Long actualAssignee = smallestEnabledUserIdOrNull("QUALITY");
    assertThat(actualAssignee).as("质量维度必然有人被指派").isNotNull();
    assertThat(mySheetIds(actualAssignee)).as("先拿到正例，避免 EXPLAIN 扫的是空表").isNotEmpty();

    // ⚠️ 先重算统计信息再 EXPLAIN：测试库是跨用例共享的，别的用例（尤其 500 供应商那条）留下的
    // 行数偏差会让优化器改选连接顺序与索引，那**不是被测行为**——本用例要判的是"05 §8.2 为待办
    // 准备的索引，在正常统计信息下会不会被选中"。不重算时实测出现过 possible_keys 里有它、
    // key 却选了 uk_sheet 的抖动（04-104）。
    jdbcTemplate.execute("ANALYZE TABLE sheet");
    jdbcTemplate.execute("ANALYZE TABLE task_supplier");
    jdbcTemplate.execute("ANALYZE TABLE eval_task");

    List<Map<String, Object>> plan =
        jdbcTemplate.queryForList(
            "EXPLAIN SELECT s.id AS sheet_id, s.task_supplier_id, ts.supplier_id, s.dimension_id,"
                + " t.id AS task_id, t.name AS task_name, t.template_version_id, t.deadline, s.status"
                + " FROM sheet s JOIN task_supplier ts ON ts.id = s.task_supplier_id"
                + " JOIN eval_task t ON t.id = ts.task_id WHERE s.assignee_id = ?"
                + " ORDER BY t.deadline, t.id, s.id",
            actualAssignee);

    assertThat(plan)
        .as("待办的过滤条件必须走 05 §8.2 为它准备的那个索引（EXPLAIN 的 key 列）")
        .anySatisfy(row -> assertThat(row.get("key")).isEqualTo("idx_sheet_assignee_status"));
  }

  // ---------- 夹具 ----------

  private static int nextYear() {
    return PERIOD_SEQ.getAndIncrement();
  }

  private TaskDraftCommand command(int year, String name, List<Long> suppliers, List<Long> approvers) {
    return new TaskDraftCommand(
        name,
        year,
        1,
        publishedDefaultTemplateId(),
        publishedDefaultVersionId(),
        LocalDate.of(year, 12, 31),
        suppliers,
        approvers);
  }

  private long createDraft(int year, List<Long> suppliers, List<Long> approvers) {
    return taskService.createDraft(command(year, "任务-" + year, suppliers, approvers));
  }

  private long publishedDefaultVersionId() {
    Long id =
        jdbcTemplate.queryForObject(
            "select v.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'",
            Long.class);
    assertThat(id).as("V8 的默认模板 v1 必须存在").isNotNull();
    return id;
  }

  private long publishedDefaultTemplateId() {
    Long id =
        jdbcTemplate.queryForObject(
            "select t.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'",
            Long.class);
    assertThat(id).as("V8 的默认模板必须存在").isNotNull();
    return id;
  }

  private long dimensionCount(long versionId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from dimension where template_version_id = ?", Long.class, versionId);
    return count == null ? 0 : count;
  }

  private long firstDimensionId(long versionId) {
    Long id =
        jdbcTemplate.queryForObject(
            "select id from dimension where template_version_id = ? order by sort_no limit 1",
            Long.class,
            versionId);
    return id == null ? -1 : id;
  }

  private long insertSupplier(String code) { return TestFixtures.insertSupplier(jdbcTemplate, code, "供应商 " + code, "W3 测试"); }

  private long insertUser(String username, String role, String department) { return TestFixtures.insertUser(jdbcTemplate, username, role, department); }

  /** 该部门当前 id 最小的在职用户；**没有任何人时返回 null**（E12 的"未指派"）。 */
  private Long smallestEnabledUserIdOrNull(String department) {
    List<Long> ids =
        jdbcTemplate.queryForList(
            "select id from sys_user where status = 'ENABLED' and department = ?"
                + " and role in ('EVALUATOR','MANAGER') order by id limit 1",
            Long.class,
            department);
    return ids.isEmpty() ? null : ids.get(0);
  }

  /** 该部门当前 id 最小的**在职**用户（13 §E12 的确定性口径；限定 EVALUATOR/MANAGER）。 */
  private long smallestEnabledUserIdIn(String department) {
    Long id =
        jdbcTemplate.queryForObject(
            "select min(id) from sys_user where status = 'ENABLED' and department = ?"
                + " and role in ('EVALUATOR','MANAGER')",
            Long.class,
            department);
    return id == null ? -1 : id;
  }

  private Map<String, Object> taskRow(long taskId) {
    return jdbcTemplate.queryForMap("select * from eval_task where id = ?", taskId);
  }

  private Map<String, Object> sheetOf(long sheetId) {
    return jdbcTemplate.queryForMap("select * from sheet where id = ?", sheetId);
  }

  private List<Long> supplierIdsOf(long taskId) {
    return jdbcTemplate.queryForList(
        "select supplier_id from task_supplier where task_id = ? order by id", Long.class, taskId);
  }

  private List<Long> approverIdsOf(long taskId) {
    return jdbcTemplate.queryForList(
        "select approver_id from approval_node where task_id = ? order by seq", Long.class, taskId);
  }

  private List<Integer> seqOf(long taskId) {
    return jdbcTemplate.queryForList(
        "select seq from approval_node where task_id = ? order by seq", Integer.class, taskId);
  }

  private long taskSupplierCount(long taskId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from task_supplier where task_id = ?", Long.class, taskId);
    return count == null ? -1 : count;
  }

  private long taskSupplierRowId(long taskId) {
    Long id =
        jdbcTemplate.queryForObject(
            "select id from task_supplier where task_id = ? order by id limit 1", Long.class, taskId);
    return id == null ? -1 : id;
  }

  private long sheetCount(long taskId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from sheet s join task_supplier ts on ts.id = s.task_supplier_id where ts.task_id = ?",
            Long.class,
            taskId);
    return count == null ? -1 : count;
  }

  private long sheetIdOfDimension(long taskId, String dimensionName) {
    Long id =
        jdbcTemplate.queryForObject(
            "select s.id from sheet s join task_supplier ts on ts.id = s.task_supplier_id"
                + " join dimension d on d.id = s.dimension_id where ts.task_id = ? and d.name = ?",
            Long.class,
            taskId,
            dimensionName);
    return id == null ? -1 : id;
  }

  private List<Long> mySheetIds(long operatorId) {
    return taskService.findMySheets(operatorId).stream().map(MySheetView::sheetId).toList();
  }
}
