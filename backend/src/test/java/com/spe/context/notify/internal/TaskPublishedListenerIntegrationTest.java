package com.spe.context.notify.internal;

import com.spe.integration.support.TestFixtures;
import static org.assertj.core.api.Assertions.assertThat;

import com.spe.common.page.PageQuery;
import com.spe.context.evaluation.internal.task.TaskDraftCommand;
import com.spe.context.evaluation.internal.task.TaskService;
import com.spe.integration.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * W3.5 通知最小版的后端（13 §E16 / 05 §5.3）：`TaskPublishedEvent` → 监听器给**被指派的评估人**
 * 各写一条通知；通知与发布**同生共死**。
 *
 * <p>判据 9 要的就是这条链路："发布后被 notify 监听器消费并写入通知（行数 ≥ 1）"。
 */
class TaskPublishedListenerIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicInteger PERIOD_SEQ = new AtomicInteger(2061);

  @Autowired private TaskService taskService;
  @Autowired private NotificationService notificationService;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  @DisplayName("W3.5 发布事件被消费：每个被指派的评估人各一条通知，未被指派的人没有通知")
  void publishWritesOneNotificationPerAssignee() {
    int year = PERIOD_SEQ.getAndIncrement();
    long supplier = insertSupplier("W3-NOTIFY-" + year);
    long approver = insertUser("w3-notify-approver-" + year, "MANAGER", "OTHER");
    insertUser("w3-notify-eval-" + year, "EVALUATOR", "QUALITY");
    insertUser("w3-notify-mgr-" + year, "MANAGER", "DELIVERY");
    long taskId = createDraftAndGetId(year, supplier, approver);

    taskService.publish(taskId, 0);

    // ⚠️ 不能断言"通知发给我刚建的那个人"：自动指派取的是**该部门 id 最小者**，而共享测试库里可能已有
    // 同部门的用户（E12 的确定性口径，不是"发给最近创建的人"）。所以从库里读**实际被指派的人**。
    long qualityEvaluator = assigneeOf(taskId, "质量");
    long deliveryManager = assigneeOf(taskId, "交付");
    assertThat(qualityEvaluator).as("质量维度一定有人被指派").isNotEqualTo(approver);

    assertThat(notificationsAbout(qualityEvaluator, "任务-" + year))
        .as("质量维度被指派给该评估人 ⇒ 必须有通知（行数 ≥ 1 是对判据 9 的直接兑现）")
        .hasSize(1);
    assertThat(notificationsAbout(qualityEvaluator, "任务-" + year).get(0).type())
        .isEqualTo(NotificationService.TYPE_TASK_PUBLISHED);
    assertThat(notificationsAbout(qualityEvaluator, "任务-" + year).get(0).content())
        .as("通知里要有任务名与周期，否则收件人不知道是哪件事")
        .contains("任务-" + year)
        .contains("Q1");
    assertThat(notificationsAbout(deliveryManager, "任务-" + year)).as("交付维度也有负责人").hasSize(1);
    assertThat(notificationsAbout(approver, "任务-" + year))
        .as("审批人没有被指派评分单 ⇒ 不该收到这条通知（发布事件只触达被指派评估人）")
        .isEmpty();
    assertThat(notificationService.unreadCount(qualityEvaluator)).isEqualTo(1L);
  }

  @Test
  @DisplayName("W3.5 全部已读只动自己的未读行（receiver 是第二层过滤）")
  void markAllReadIsScopedToTheReceiver() {
    int year = PERIOD_SEQ.getAndIncrement();
    long supplier = insertSupplier("W3-READ-" + year);
    long approver = insertUser("w3-read-approver-" + year, "MANAGER", "OTHER");
    insertUser("w3-read-eval-" + year, "EVALUATOR", "QUALITY");
    insertUser("w3-read-mgr-" + year, "MANAGER", "DELIVERY");
    long taskId = createDraftAndGetId(year, supplier, approver);
    taskService.publish(taskId, 0);
    long qualityEvaluator = assigneeOf(taskId, "质量");
    long deliveryManager = assigneeOf(taskId, "交付");

    int changed = notificationService.markAllRead(qualityEvaluator);

    assertThat(changed).as("至少标记了本用例刚写的那一条").isGreaterThanOrEqualTo(1);
    assertThat(notificationService.unreadCount(qualityEvaluator)).as("自己的未读清零").isZero();
    assertThat(notificationService.unreadCount(deliveryManager))
        .as("别人的未读不受影响（receiver 是第二层过滤）")
        .isGreaterThanOrEqualTo(1L);
    assertThat(notificationsAbout(qualityEvaluator, "任务-" + year))
        .as("已读之后列表里仍然看得到（只是未读角标清零）")
        .hasSize(1);
  }

  @Test
  @DisplayName("W3.5 通知与发布同事务：发布被外层事务回滚 ⇒ 通知、评分单、任务状态一起回滚")
  void notificationsRollBackWithThePublishTransaction() {
    int year = PERIOD_SEQ.getAndIncrement();
    long supplier = insertSupplier("W3-TX-" + year);
    long approver = insertUser("w3-tx-approver-" + year, "MANAGER", "OTHER");
    insertUser("w3-tx-eval-" + year, "EVALUATOR", "QUALITY");
    long taskId = createDraftAndGetId(year, supplier, approver);
    // 发布前先解析"将被指派的人"：自动指派在 publish 里发生，读被指派人要**发布之后**才查得到，
    // 所以这里先记下当时的"部门内 id 最小者"，用作断言的收件人
    long qualityEvaluator = smallestEnabledUserIdIn("QUALITY");

    new TransactionTemplate(txManager)
        .execute(
            status -> {
              taskService.publish(taskId, 0);
              assertThat(notificationsAbout(qualityEvaluator, "任务-" + year))
                  .as("同一事务内应当看得见监听器写的通知")
                  .hasSize(1);
              status.setRollbackOnly();
              return null;
            });

    assertThat(notificationsAbout(qualityEvaluator, "任务-" + year))
        .as("外层回滚 ⇒ 通知一并消失（同事务）")
        .isEmpty();
    assertThat(sheetsOfTask(taskId)).as("评分单也回滚了").isZero();
    assertThat(jdbcTemplate.queryForObject("select status from eval_task where id = ?", String.class, taskId))
        .isEqualTo("DRAFT");

    // 正例：回滚之后仍能正常发布（没有留下半成品挡住第二次）
    taskService.publish(taskId, 0);
    assertThat(notificationsAbout(qualityEvaluator, "任务-" + year)).hasSize(1);
    assertThat(sheetsOfTask(taskId)).isGreaterThan(0);
  }

  // ---------- 夹具 ----------

  /** 从库里读某维度**实际被指派**的人（自动指派按"部门内 id 最小者"，不是"最近创建的人"）。 */
  /** 该部门当前 id 最小的在职评估人/经理（E12 的确定口径；发布前查，用于断言收件人）。 */
  private long smallestEnabledUserIdIn(String department) {
    Long id =
        jdbcTemplate.queryForObject(
            "select min(id) from sys_user where status = 'ENABLED' and department = ?"
                + " and role in ('EVALUATOR','MANAGER')",
            Long.class,
            department);
    return id == null ? -1 : id;
  }

  private long assigneeOf(long taskId, String dimensionName) {
    Long id =
        jdbcTemplate.queryForObject(
            "select s.assignee_id from sheet s"
                + " join task_supplier ts on ts.id = s.task_supplier_id"
                + " join dimension d on d.id = s.dimension_id"
                + " where ts.task_id = ? and d.name = ?",
            Long.class,
            taskId,
            dimensionName);
    return id == null ? -1 : id;
  }

  private long createDraftAndGetId(int year, long supplier, long approver) {
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
    return taskService.createDraft(
        new TaskDraftCommand(
            "任务-" + year,
            year,
            1,
            versionId == null ? -1 : templateId,
            versionId == null ? -1 : versionId,
            LocalDate.of(year, 12, 31),
            List.of(supplier),
            List.of(approver)));
  }

  private long insertSupplier(String code) { return TestFixtures.insertSupplier(jdbcTemplate, code, "供应商 " + code, "W3 测试"); }

  private long insertUser(String username, String role, String department) { return TestFixtures.insertUser(jdbcTemplate, username, role, department); }

  /**
   * 本用例自己制造的通知（按任务名过滤）：共享测试库里同一个收件人会收到**多个用例**的通知，
   * 直接对全表断言会随执行顺序漂移（CLAUDE 默认约定第 5 条：把范围收敛到本用例建立的数据）。
   */
  private List<NotificationSummary> notificationsAbout(long receiverId, String taskName) {
    return notificationsOf(receiverId).stream()
        .filter(summary -> summary.content() != null && summary.content().contains(taskName))
        .toList();
  }

  private List<NotificationSummary> notificationsOf(long receiverId) {
    return notificationService.page(receiverId, PageQuery.of(1, 50)).list();
  }

  private long sheetsOfTask(long taskId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from sheet s join task_supplier ts on ts.id = s.task_supplier_id where ts.task_id = ?",
            Long.class,
            taskId);
    return count == null ? -1 : count;
  }
}
