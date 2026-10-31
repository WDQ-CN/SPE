package com.spe.context.evaluation.internal.approval;

import com.spe.integration.support.TestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.TaskSupplierStatus;
import com.spe.context.evaluation.api.events.TaskSupplierFinalizedEvent;
import com.spe.context.evaluation.internal.finalize.FinalizeService;
import com.spe.context.evaluation.internal.unlock.UnlockService;
import com.spe.context.evaluation.internal.result.ResultReportService;
import com.spe.context.evaluation.internal.result.TaskSupplierReport;
import com.spe.context.evaluation.internal.task.TaskDraftCommand;
import com.spe.context.evaluation.internal.task.TaskService;
import com.spe.context.evaluation.internal.task.TaskSupplier;
import com.spe.context.notify.internal.NotificationService;
import com.spe.context.notify.internal.NotificationSummary;
import com.spe.context.system.api.GradeThresholds;
import com.spe.context.system.internal.setting.SettingService;
import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * P4-W3/W4：审批流转（T5）与定稿（T3）的集成用例（15 §E5/E6、16 §W3/W4）。
 *
 * <p>夹具走真实 `TaskService` 创建并发布任务（审批链与评分单都是真的），然后把评分单补齐分数、
 * 置为已提交、把评估单推到「待审批」——T5/T3 的入口状态用 SQL 造，是因为这两条链路只该覆盖审批本身；
 * T2 的提交语义由 P3 的用例守着。
 *
 * <p>所有断言都按**本用例自己的 taskSupplierId**收敛（CLAUDE 默认约定第 5 条）。
 */
@RecordApplicationEvents
class ApprovalFlowIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicInteger YEAR_SEQ = new AtomicInteger(2090);

  @Autowired private TaskService taskService;
  @Autowired private ApprovalService approvalService;
  @Autowired private FinalizeService finalizeService;
  @Autowired private ResultReportService resultReportService;
  @Autowired private NotificationService notificationService;
  @Autowired private SettingService settingService;
  @Autowired private UnlockService unlockService;
  @Autowired private ApplicationEvents applicationEvents;
  @Autowired private com.spe.context.evaluation.internal.task.TaskSupplierMapper taskSupplierMapper;

  @Test
  @DisplayName("W6 报告 actableNow（P2-27）：只有「当前节点审批人」拿到 true，评分中与生效后都是 false")
  void reportFlagsOnlyTheCurrentApproverAsActable() {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    jdbcTemplate.update(
        "update task_supplier set status = 'PENDING_APPROVAL', current_node = 1 where id = ?", tsId);

    TaskSupplierReport asCurrent = resultReportService.report(tsId, prepared.approverIds().get(0), true);
    assertThat(asCurrent.actableNow()).as("当前节点审批人").isTrue();
    assertThat(asCurrent.currentNode()).isEqualTo(1);

    TaskSupplierReport asOther = resultReportService.report(tsId, prepared.approverIds().get(1), true);
    assertThat(asOther.actableNow()).as("同角色但不在当前节点").isFalse();

    jdbcTemplate.update("update task_supplier set status = 'SCORING', current_node = 0 where id = ?", tsId);
    assertThat(resultReportService.report(tsId, prepared.approverIds().get(0), true).actableNow())
        .as("评分中不可审批")
        .isFalse();
  }

  @Test
  @DisplayName("W3 我的待办：每个审批人只看到「当前节点是我」的评估单")
  void myApprovalsAreScopedToTheCurrentNode() {
    Prepared prepared = prepareTask(3, 3);
    List<Long> tsIds = prepared.taskSupplierIds();
    for (int i = 0; i < tsIds.size(); i++) {
      // 第 1 单卡在第 1 级，第 2 单卡在第 2 级，第 3 单卡在第 3 级
      jdbcTemplate.update(
          "update task_supplier set status = ?, current_node = ? where id = ?",
          i == 0 ? "PENDING_APPROVAL" : "APPROVING",
          i + 1,
          tsIds.get(i));
    }

    for (int i = 0; i < prepared.approverIds().size(); i++) {
      PageResult<ApprovalTodoView> page =
          approvalService.listMyApprovals(prepared.approverIds().get(i), PageQuery.of(1, 20));
      assertThat(page.total()).as("第 %d 级审批人必须有且只有 1 条待办", i + 1).isEqualTo(1);
      assertThat(page.list()).hasSize(1);
      ApprovalTodoView row = page.list().get(0);
      assertThat(row.taskSupplierId()).isEqualTo(tsIds.get(i));
      assertThat(row.currentNode()).isEqualTo(i + 1);
      assertThat(row.supplierName()).as("供应商名必须经门面补齐").isNotBlank();
      assertThat(row.version()).as("审批动作要用它做乐观锁").isZero();
    }
  }

  @Test
  @DisplayName("W3 非末级通过：状态转审批中、节点 +1、记录 1 条、通知下一级")
  void approveMovesToNextNodeAndNotifiesTheNextApprover() {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    long approver1 = prepared.approverIds().get(0);

    approvalService.approve(tsId, approver1, 0);

    Map<String, Object> row = taskSupplierRow(tsId);
    assertThat(row.get("status")).isEqualTo("APPROVING");
    assertThat(((Number) row.get("current_node")).intValue()).isEqualTo(2);
    assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);
    assertThat(row.get("total_score")).as("非末级通过还不该定稿").isNull();

    List<Map<String, Object>> records = approvalRecords(tsId);
    assertThat(records).as("一级通过只写一条记录").hasSize(1);
    assertThat(records.get(0).get("round")).isEqualTo(1);
    assertThat(records.get(0).get("seq")).isEqualTo(1);
    assertThat(records.get(0).get("action")).isEqualTo("APPROVE");
    assertThat(((Number) records.get(0).get("approver_id")).longValue()).isEqualTo(approver1);

    assertThat(notifications(prepared.approverIds().get(1), NotificationService.TYPE_APPROVAL_NODE_PASSED, "#" + tsId))
        .as("下一级审批人必须收到流转通知")
        .hasSize(1);
    assertThat(auditCount("APPROVAL_APPROVE", tsId)).isEqualTo(1);
  }

  @Test
  @DisplayName("W4 末级通过即定稿：EFFECTIVE + 总分/等级 + 维度快照 + 任务完成 + 通知采购经理")
  void lastApproveFinalizesAndCompletesTheTask() throws Exception {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    long supplierId = prepared.supplierIds().get(0);

    approvalService.approve(tsId, prepared.approverIds().get(0), 0);
    approvalService.approve(tsId, prepared.approverIds().get(1), 1);

    Map<String, Object> row = taskSupplierRow(tsId);
    assertThat(row.get("status")).isEqualTo("EFFECTIVE");
    assertThat(((Number) row.get("current_node")).intValue()).isEqualTo(2);
    assertThat(new BigDecimal(row.get("total_score").toString()))
        .as("夹具让每个指标都换算成 80 ⇒ 总分 80.00")
        .isEqualByComparingTo("80.00");
    assertThat(row.get("total_grade")).isEqualTo("B");
    assertThat(approvalRecords(tsId)).as("两级各一条记录").hasSize(2);

    long dimensions = dimensionCountOf(prepared.templateVersionId());
    assertThat(dimensions).as("默认模板必须有维度，否则快照判据没意义").isGreaterThan(0);
    assertThat(count("select count(*) from task_supplier_dimension_result where task_supplier_id = ?", tsId))
        .as("快照行数 = 维度数")
        .isEqualTo(dimensions);
    assertThat(
            count(
                "select count(*) from task_supplier_dimension_result"
                    + " where task_supplier_id = ? and score is null",
                tsId))
        .as("定稿不允许空维度（15 §E13）")
        .isZero();

    assertThat(taskStatus(prepared.taskId())).as("唯一供应商生效 ⇒ 任务自动完成").isEqualTo("COMPLETED");
    assertThat(auditCount("TASK_SUPPLIER_FINALIZE", tsId)).isEqualTo(1);
    String finalizeDetail =
        jdbcTemplate.queryForObject(
            "select detail from audit_log where action = 'TASK_SUPPLIER_FINALIZE' and target_id = ?",
            String.class,
            tsId);
    assertThat(objectMapper.readTree(finalizeDetail).path("taskCompleted").asBoolean())
        .as("完单结果并入留痕（15 §E10）")
        .isTrue();

    long manager = prepared.approverIds().get(0);
    assertThat(
            notifications(
                manager, NotificationService.TYPE_TASK_SUPPLIER_FINALIZED, "供应商 #" + supplierId))
        .as("采购经理收到生效通知")
        .hasSize(1);
  }

  @Test
  @DisplayName("本期首次生效：首轮定稿 true；解锁重评再定稿 false（01 W2）")
  void firstEffectiveOnlyForTheFirstFinalize() {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    long approver1 = prepared.approverIds().get(0);
    long approver2 = prepared.approverIds().get(1);

    approvalService.approve(tsId, approver1, 0);
    approvalService.approve(tsId, approver2, 1);
    assertThat(finalizedEvents(tsId))
        .singleElement()
        .satisfies(event -> assertThat(event.firstEffective()).isTrue());

    int version = ((Number) taskSupplierRow(tsId).get("version")).intValue();
    unlockService.unlock(tsId, version, null);

    jdbcTemplate.update(
        "update sheet set status = 'SUBMITTED', version = version + 1 where task_supplier_id = ?",
        tsId);
    jdbcTemplate.update(
        "update task_supplier set status = 'PENDING_APPROVAL', current_node = 1,"
            + " version = version + 1 where id = ?",
        tsId);

    int versionAfterResubmit = ((Number) taskSupplierRow(tsId).get("version")).intValue();
    approvalService.approve(tsId, approver1, versionAfterResubmit);
    int versionAfterFirstNode = ((Number) taskSupplierRow(tsId).get("version")).intValue();
    approvalService.approve(tsId, approver2, versionAfterFirstNode);

    List<TaskSupplierFinalizedEvent> events = finalizedEvents(tsId);
    assertThat(events).hasSize(2);
    assertThat(events.get(0).firstEffective()).isTrue();
    assertThat(events.get(1).firstEffective())
        .as("解锁重评再定稿不算本期首次生效，趋势类预警不得重新触发")
        .isFalse();
  }

  private List<TaskSupplierFinalizedEvent> finalizedEvents(long taskSupplierId) {
    return applicationEvents.stream(TaskSupplierFinalizedEvent.class)
        .filter(event -> event.taskSupplierId() == taskSupplierId)
        .toList();
  }

  @Test
  @DisplayName("W3 驳回：状态回评分中、节点清零、轮次 +1、解锁全部已提交评分单、通知评估人")
  void rejectUnlocksSubmittedSheetsAndIncrementsRound() throws Exception {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    List<Long> sheetIds = sheetIdsOf(tsId);
    assertThat(sheetIds).as("默认模板每单 4 张评分单").hasSize(4);

    approvalService.reject(tsId, prepared.approverIds().get(0), "  质量数据不达标，请重填  ", 0);

    Map<String, Object> row = taskSupplierRow(tsId);
    assertThat(row.get("status")).isEqualTo("SCORING");
    assertThat(((Number) row.get("current_node")).intValue()).isZero();
    assertThat(((Number) row.get("round")).intValue()).as("驳回必须把轮次 +1").isEqualTo(2);
    assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);
    assertThat(
            count(
                "select count(*) from sheet where task_supplier_id = ? and status = 'SCORING'",
                tsId))
        .as("4 张已提交评分单全部解锁（守卫条件是 SUBMITTED）")
        .isEqualTo(4);

    List<Map<String, Object>> records = approvalRecords(tsId);
    assertThat(records).hasSize(1);
    assertThat(records.get(0).get("round")).as("记录写驳回前的轮次").isEqualTo(1);
    assertThat(records.get(0).get("seq")).isEqualTo(1);
    assertThat(records.get(0).get("action")).isEqualTo("REJECT");
    assertThat(records.get(0).get("comment")).as("意见首尾空白被 trim").isEqualTo("质量数据不达标，请重填");

    assertThat(
            notifications(
                prepared.evaluatorId(),
                NotificationService.TYPE_TASK_SUPPLIER_REJECTED,
                "#" + tsId))
        .as("评估人收到驳回通知")
        .hasSize(1)
        .allSatisfy(summary -> assertThat(summary.content()).contains("质量数据不达标"));
    String auditDetail =
        jdbcTemplate.queryForObject(
            "select detail from audit_log where action = 'APPROVAL_REJECT' and target_id = ?",
            String.class,
            tsId);
    assertThat(objectMapper.readTree(auditDetail).path("unlockedSheets").asInt()).isEqualTo(4);
    assertThat(objectMapper.readTree(auditDetail).path("comment").asText())
        .isEqualTo("质量数据不达标，请重填");
  }

  @Test
  @DisplayName("W3 重提后第二轮：round 递增让同一节点能写第二条记录（不撞唯一键）")
  void resubmitThenRejectWritesSecondRoundRecord() {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    approvalService.reject(tsId, prepared.approverIds().get(0), "第一轮驳回", 0);

    // 模拟重提：评分单再次提交、评估单回到第一级待审（T2 的语义已由 P3 用例覆盖）
    jdbcTemplate.update(
        "update sheet set status = 'SUBMITTED', version = version + 1 where task_supplier_id = ?",
        tsId);
    jdbcTemplate.update(
        "update task_supplier set status = 'PENDING_APPROVAL', current_node = 1 where id = ?", tsId);

    approvalService.reject(tsId, prepared.approverIds().get(0), "第二轮驳回", 1);

    List<Map<String, Object>> records = approvalRecords(tsId);
    assertThat(records).as("两轮各一条").hasSize(2);
    assertThat(records.stream().map(row -> ((Number) row.get("round")).intValue()).toList())
        .containsExactly(1, 2);
    assertThat(((Number) taskSupplierRow(tsId).get("round")).intValue()).isEqualTo(3);
  }

  @Test
  @DisplayName("W3 越权与冲突：非当前节点 4003、版本不符 3001、空意见 2029，状态与记录都不动")
  void authorizationAndConflictFailuresLeaveStateUntouched() {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    long approver1 = prepared.approverIds().get(0);
    long approver2 = prepared.approverIds().get(1);

    BizException wrongNode =
        catchThrowableOfType(() -> approvalService.approve(tsId, approver2, 0), BizException.class);
    assertThat(wrongNode).isNotNull();
    assertThat(wrongNode.errorCode()).isEqualTo(ErrorCode.APPROVAL_NOT_YOUR_NODE);

    BizException wrongVersion =
        catchThrowableOfType(() -> approvalService.approve(tsId, approver1, 99), BizException.class);
    assertThat(wrongVersion).isNotNull();
    assertThat(wrongVersion.errorCode()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT);

    BizException emptyComment =
        catchThrowableOfType(
            () -> approvalService.reject(tsId, approver1, "   ", 0), BizException.class);
    assertThat(emptyComment).isNotNull();
    assertThat(emptyComment.errorCode()).isEqualTo(ErrorCode.APPROVAL_COMMENT_REQUIRED);

    BizException tooLong =
        catchThrowableOfType(
            () -> approvalService.reject(tsId, approver1, "意".repeat(501), 0), BizException.class);
    assertThat(tooLong).isNotNull();
    assertThat(tooLong.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);

    Map<String, Object> row = taskSupplierRow(tsId);
    assertThat(row.get("status")).isEqualTo("PENDING_APPROVAL");
    assertThat(((Number) row.get("current_node")).intValue()).isEqualTo(1);
    assertThat(((Number) row.get("version")).intValue()).isZero();
    assertThat(approvalRecords(tsId)).as("失败路径不许留下半条记录").isEmpty();
    assertThat(
            count(
                "select count(*) from sheet where task_supplier_id = ? and status <> 'SUBMITTED'",
                tsId))
        .as("失败路径不许解锁评分单")
        .isZero();
  }

  @Test
  @DisplayName("W4 定稿回滚：快照写入失败 ⇒ EFFECTIVE/分数/审计/通知全部回滚")
  void finalizeFailureRollsBackTheWholeTransaction() {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    approvalService.approve(tsId, prepared.approverIds().get(0), 0);

    // 故障注入：预置一条与定稿写入同键的维度快照 ⇒ 定稿的 insertBatch 撞 uk_ts_dimension
    Long dimensionId =
        jdbcTemplate.queryForObject(
            "select id from dimension where template_version_id = ? order by sort_no limit 1",
            Long.class,
            prepared.templateVersionId());
    jdbcTemplate.update(
        "insert into task_supplier_dimension_result"
            + " (task_supplier_id, dimension_id, dimension_name, weight, score, grade)"
            + " values (?, ?, '预置', 1.00, 1.00, 'D')",
        tsId,
        dimensionId);

    Throwable failure =
        catchThrowableOfType(
            () -> approvalService.approve(tsId, prepared.approverIds().get(1), 1), Throwable.class);
    assertThat(failure).as("重复键必须炸出来").isNotNull();

    Map<String, Object> row = taskSupplierRow(tsId);
    assertThat(row.get("status")).as("EFFECTIVE 没落").isEqualTo("APPROVING");
    assertThat(((Number) row.get("current_node")).intValue()).isEqualTo(2);
    assertThat(((Number) row.get("version")).intValue()).as("定稿的 version+1 回滚").isEqualTo(1);
    assertThat(row.get("total_score")).isNull();
    assertThat(approvalRecords(tsId)).as("末级审批记录与定稿同事务，一起回滚").hasSize(1);
    assertThat(
            count(
                "select count(*) from task_supplier_dimension_result where task_supplier_id = ?",
                tsId))
        .as("只剩预置的那一行")
        .isEqualTo(1);
    assertThat(auditCount("TASK_SUPPLIER_FINALIZE", tsId)).isZero();
    assertThat(taskStatus(prepared.taskId())).isEqualTo("IN_PROGRESS");
    assertThat(
            notifications(
                prepared.approverIds().get(0),
                NotificationService.TYPE_TASK_SUPPLIER_FINALIZED,
                "#" + tsId))
        .as("事件没发，通知也不该有")
        .isEmpty();
  }

  @Test
  @DisplayName("W4 定级阈值来自 SettingsFacade：把 A 线下调到 80 ⇒ 同一个 80 分定成 A")
  void finalizeReadsThresholdsFromSettings() {
    GradeThresholds original = settingService.gradeThresholds();
    try {
      settingService.updateGradeThresholds(
          new GradeThresholds(
              new BigDecimal("80"), new BigDecimal("70"), new BigDecimal("60"), BigDecimal.ZERO));

      Prepared prepared = prepareTask(1, 2);
      long tsId = prepared.taskSupplierIds().get(0);
      approvalService.approve(tsId, prepared.approverIds().get(0), 0);
      approvalService.approve(tsId, prepared.approverIds().get(1), 1);

      Map<String, Object> row = taskSupplierRow(tsId);
      assertThat(new BigDecimal(row.get("total_score").toString())).isEqualByComparingTo("80.00");
      assertThat(row.get("total_grade"))
          .as("阈值不是硬编码：80 分在 A≥80 下必须是 A")
          .isEqualTo("A");
    } finally {
      settingService.updateGradeThresholds(original);
    }
  }

  @Test
  @DisplayName("W3 解锁守卫：只把已提交的评分单放回评分中，未提交的那张不动版本")
  void rejectOnlyUnlocksSubmittedSheets() throws Exception {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    List<Long> sheetIds = sheetIdsOf(tsId);
    long untouched = sheetIds.get(0);
    jdbcTemplate.update("update sheet set status = 'SCORING' where id = ?", untouched);

    approvalService.reject(tsId, prepared.approverIds().get(0), "只解锁已提交的", 0);

    assertThat(count("select count(*) from sheet where task_supplier_id = ? and version = 1", tsId))
        .as("4 张里只有 3 张被解锁（守卫条件是 status='SUBMITTED'）")
        .isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForObject(
                "select version from sheet where id = ?", Integer.class, untouched))
        .as("未提交的那张不该被白改版本")
        .isZero();
    assertThat(
            objectMapper
                .readTree(
                    jdbcTemplate.queryForObject(
                        "select detail from audit_log where action = 'APPROVAL_REJECT' and target_id = ?",
                        String.class,
                        tsId))
                .path("unlockedSheets")
                .asInt())
        .isEqualTo(3);
  }

  @Test
  @DisplayName("W4 定稿守卫：过期版本 / 错节点 / 错状态都 3001，且不落任何定稿副作用")
  void finalizeRejectsStaleVersionWrongNodeAndWrongStatus() {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    approvalService.approve(tsId, prepared.approverIds().get(0), 0);

    TaskSupplier afterFirst = taskSupplierMapper.findById(tsId).orElseThrow();

    BizException staleVersion =
        catchThrowableOfType(
            () -> finalizeService.finalize(afterFirst, 2, 0), BizException.class);
    assertThat(staleVersion).isNotNull();
    assertThat(staleVersion.errorCode()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT);

    BizException wrongNode =
        catchThrowableOfType(
            () -> finalizeService.finalize(afterFirst, 1, 1), BizException.class);
    assertThat(wrongNode).isNotNull();
    assertThat(wrongNode.errorCode()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT);

    jdbcTemplate.update("update task_supplier set status = 'SCORING' where id = ?", tsId);
    TaskSupplier wrongStatus = taskSupplierMapper.findById(tsId).orElseThrow();
    BizException statusFailure =
        catchThrowableOfType(
            () -> finalizeService.finalize(wrongStatus, 2, 1), BizException.class);
    assertThat(statusFailure).isNotNull();
    assertThat(statusFailure.errorCode()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT);

    Map<String, Object> row = taskSupplierRow(tsId);
    assertThat(row.get("status")).as("守卫全部未命中 ⇒ 状态不动").isEqualTo("SCORING");
    assertThat(row.get("total_score")).isNull();
    assertThat(
            count(
                "select count(*) from task_supplier_dimension_result where task_supplier_id = ?",
                tsId))
        .as("一次快照都不该写")
        .isZero();
    assertThat(auditCount("TASK_SUPPLIER_FINALIZE", tsId)).isZero();
  }

  @Test
  @DisplayName("W4 任务完成的两个方向：还有一个未生效 ⇒ 仍 IN_PROGRESS；全部生效 ⇒ COMPLETED")
  void taskCompletesOnlyWhenEverySupplierIsEffective() {
    Prepared prepared = prepareTask(2, 2);
    List<Long> tsIds = prepared.taskSupplierIds();
    long approver1 = prepared.approverIds().get(0);
    long approver2 = prepared.approverIds().get(1);

    approvalService.approve(tsIds.get(0), approver1, 0);
    approvalService.approve(tsIds.get(0), approver2, 1);
    assertThat(taskStatus(prepared.taskId()))
        .as("还有第二个供应商未生效 ⇒ 任务不能完成")
        .isEqualTo("IN_PROGRESS");

    approvalService.approve(tsIds.get(1), approver1, 0);
    approvalService.approve(tsIds.get(1), approver2, 1);
    assertThat(taskStatus(prepared.taskId()))
        .as("全部评估单已生效 ⇒ 任务自动完成")
        .isEqualTo("COMPLETED");
  }

  @Test
  @DisplayName("W5 审批留痕：通过/驳回/定稿三类动作落库，detail 不含口令/令牌")
  void approvalAuditTrailCoversThreeActionsWithoutSecrets() {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    long approver1 = prepared.approverIds().get(0);
    long approver2 = prepared.approverIds().get(1);

    approvalService.reject(tsId, approver1, "意见摘要测试", 0);
    jdbcTemplate.update(
        "update sheet set status = 'SUBMITTED', version = version + 1 where task_supplier_id = ?",
        tsId);
    jdbcTemplate.update(
        "update task_supplier set status = 'PENDING_APPROVAL', current_node = 1 where id = ?", tsId);
    approvalService.approve(tsId, approver1, 1);
    approvalService.approve(tsId, approver2, 2);

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select action, target_type, target_id, detail from audit_log"
                + " where target_id = ? and action in"
                + " ('APPROVAL_APPROVE','APPROVAL_REJECT','TASK_SUPPLIER_FINALIZE')"
                + " order by id",
            tsId);
    assertThat(rows).as("驳回 1 + 两级通过各 1 + 定稿 1 = 4 条").hasSize(4);
    assertThat(rows.stream().map(row -> row.get("action")).toList())
        .containsExactly(
            "APPROVAL_REJECT", "APPROVAL_APPROVE", "APPROVAL_APPROVE", "TASK_SUPPLIER_FINALIZE");
    assertThat(rows).allSatisfy(row -> assertThat(row.get("target_type")).isEqualTo("TASK_SUPPLIER"));

    String details =
        rows.stream().map(row -> String.valueOf(row.get("detail"))).collect(Collectors.joining("\n"));
    assertThat(details.toLowerCase())
        .as("审计 detail 不得出现凭据类字段")
        .doesNotContain("password")
        .doesNotContain("token")
        .doesNotContain("secret")
        .doesNotContain("eyj");
    assertThat(details).contains("意见摘要测试").contains("\"unlockedSheets\"").contains("totalScore");
  }

  @Test
  @DisplayName("W3 并发通过同一节点：X 锁串行化，只有一人成功、只留一条记录")
  void concurrentApproveOnlyOneWins() throws Exception {
    Prepared prepared = prepareTask(1, 2);
    long tsId = prepared.taskSupplierIds().get(0);
    long approver = prepared.approverIds().get(0);
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<ErrorCode>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(
            pool.submit(
                () -> {
                  barrier.await();
                  BizException failure =
                      catchThrowableOfType(
                          () -> approvalService.approve(tsId, approver, 0), BizException.class);
                  return failure == null ? null : failure.errorCode();
                }));
      }
      List<ErrorCode> failures = new ArrayList<>();
      for (Future<ErrorCode> future : futures) {
        failures.add(future.get());
      }
      long succeeded = failures.stream().filter(code -> code == null).count();
      assertThat(succeeded).as("必须恰好一人成功").isEqualTo(1);
      assertThat(failures).filteredOn(code -> code != null)
          .as("后到者看到节点已推进 ⇒ 4003（不是 5xxx）")
          .containsExactly(ErrorCode.APPROVAL_NOT_YOUR_NODE);
    } finally {
      pool.shutdownNow();
    }
    assertThat(approvalRecords(tsId)).hasSize(1);
    assertThat(((Number) taskSupplierRow(tsId).get("current_node")).intValue()).isEqualTo(2);
  }

  // ---------- 夹具 ----------

  private record Prepared(
      long taskId,
      long templateVersionId,
      List<Long> taskSupplierIds,
      List<Long> supplierIds,
      List<Long> approverIds,
      long evaluatorId) {}

  private Prepared prepareTask(int supplierCount, int approverCount) {
    int year = YEAR_SEQ.getAndIncrement();
    long templateVersionId = publishedDefaultVersionId();
    List<Long> supplierIds = new ArrayList<>();
    for (int i = 0; i < supplierCount; i++) {
      supplierIds.add(insertSupplier("P4-" + year + "-" + i));
    }
    List<Long> approverIds = new ArrayList<>();
    for (int i = 0; i < approverCount; i++) {
      approverIds.add(insertUser("p4-approver-" + year + "-" + i, "MANAGER", "OTHER"));
    }
    long evaluatorId = insertUser("p4-evaluator-" + year, "EVALUATOR", "QUALITY");

    long taskId =
        taskService.createDraft(
            new TaskDraftCommand(
                "P4 审批任务-" + year,
                year,
                1,
                publishedDefaultTemplateId(),
                templateVersionId,
                LocalDate.of(year, 12, 31),
                supplierIds,
                approverIds));
    taskService.publish(taskId, 0);

    List<Long> taskSupplierIds =
        jdbcTemplate.queryForList(
            "select id from task_supplier where task_id = ? order by id", Long.class, taskId);
    assertThat(taskSupplierIds).hasSize(supplierCount);
    for (Long tsId : taskSupplierIds) {
      fillScoresAndSubmit(tsId, evaluatorId);
    }
    return new Prepared(
        taskId, templateVersionId, taskSupplierIds, supplierIds, approverIds, evaluatorId);
  }

  /** 给该评估单的每张评分单补齐分数（每指标都换算成 80）并置为已提交，评估单推到第一级待审。 */
  private void fillScoresAndSubmit(long taskSupplierId, long evaluatorId) {
    List<Long> sheetIds =
        jdbcTemplate.queryForList(
            "select id from sheet where task_supplier_id = ? order by id",
            Long.class,
            taskSupplierId);
    assertThat(sheetIds).isNotEmpty();
    for (Long sheetId : sheetIds) {
      List<Map<String, Object>> indicators =
          jdbcTemplate.queryForList(
              "select i.id, i.type, i.score_mode, i.target_value from indicator i"
                  + " join sheet s on s.dimension_id = i.dimension_id where s.id = ?",
              sheetId);
      assertThat(indicators).as("每张评分单至少有 1 个指标").isNotEmpty();
      for (Map<String, Object> indicator : indicators) {
        BigDecimal rawValue = rawValueForEighty(indicator);
        jdbcTemplate.update(
            "insert into score (sheet_id, indicator_id, raw_value, score_value, source)"
                + " values (?, ?, ?, 80.00, 'MANUAL')",
            sheetId,
            ((Number) indicator.get("id")).longValue(),
            rawValue);
      }
    }
    jdbcTemplate.update(
        "update sheet set assignee_id = ?, status = 'SUBMITTED' where task_supplier_id = ?",
        evaluatorId,
        taskSupplierId);
    jdbcTemplate.update(
        "update task_supplier set status = 'PENDING_APPROVAL', current_node = 1 where id = ?",
        taskSupplierId);
  }

  /** 让每个指标恰好换算成 80：定性 4；TARGET 取目标值 × 0.8；其余 80。 */
  private static BigDecimal rawValueForEighty(Map<String, Object> indicator) {
    String type = String.valueOf(indicator.get("type"));
    if ("QUAL".equals(type)) {
      return new BigDecimal("4");
    }
    if ("TARGET".equals(String.valueOf(indicator.get("score_mode")))) {
      BigDecimal target = new BigDecimal(indicator.get("target_value").toString());
      return target.multiply(new BigDecimal("0.8")).setScale(2, RoundingMode.HALF_UP);
    }
    return new BigDecimal("80");
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

  private long insertSupplier(String code) { return TestFixtures.insertSupplier(jdbcTemplate, code, "供应商 " + code, "P4 测试"); }

  private long insertUser(String username, String role, String department) { return TestFixtures.insertUser(jdbcTemplate, username, role, department); }

  private Map<String, Object> taskSupplierRow(long taskSupplierId) {
    return jdbcTemplate.queryForMap("select * from task_supplier where id = ?", taskSupplierId);
  }

  private String taskStatus(long taskId) {
    return jdbcTemplate.queryForObject("select status from eval_task where id = ?", String.class, taskId);
  }

  private List<Long> sheetIdsOf(long taskSupplierId) {
    return jdbcTemplate.queryForList(
        "select id from sheet where task_supplier_id = ? order by id", Long.class, taskSupplierId);
  }

  private List<Map<String, Object>> approvalRecords(long taskSupplierId) {
    return jdbcTemplate.queryForList(
        "select * from approval_record where task_supplier_id = ? order by round, seq",
        taskSupplierId);
  }

  private long dimensionCountOf(long templateVersionId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from dimension where template_version_id = ?",
            Long.class,
            templateVersionId);
    return count == null ? -1 : count;
  }

  private long auditCount(String action, long targetId) {
    return count("select count(*) from audit_log where action = ? and target_id = ?", action, targetId);
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? -1 : value;
  }

  private List<NotificationSummary> notifications(long receiverId, String type, String marker) {
    return notificationService.page(receiverId, PageQuery.of(1, 50)).list().stream()
        .filter(summary -> type.equals(summary.type()))
        .filter(summary -> summary.content() != null && summary.content().contains(marker))
        .toList();
  }
}
