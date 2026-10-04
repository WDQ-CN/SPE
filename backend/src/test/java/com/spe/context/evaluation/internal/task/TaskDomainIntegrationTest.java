package com.spe.context.evaluation.internal.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.internal.sheet.MySheetView;
import com.spe.context.evaluation.internal.sheet.Sheet;
import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * W3 任务域的集成用例（真库 + 真事务）：创建/编辑草稿（W3.1）、发布 T1（W3.2）、自动指派（W3.3）、
 * 改派与可见性（W3.4）。
 *
 * <p>每条用例用**自己的周期**（年份递增）与**自己的供应商编码**：J4 是按"供应商 + 周期"判重的，
 * 共享同一个周期会让用例之间互相干扰（P1 的教训：共享库上不要断言全局状态）。
 */
class TaskDomainIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicInteger PERIOD_SEQ = new AtomicInteger(2031);

  @Autowired private TaskService taskService;

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
        name, year, 1, publishedDefaultVersionId(), LocalDate.of(year, 12, 31), suppliers, approvers);
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

  private long insertSupplier(String code) {
    jdbcTemplate.update(
        "insert into supplier (code, name, category, status, version) values (?, ?, 'W3 测试', 'ENABLED', 0)",
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
