package com.spe.context.notify.internal;

import com.spe.integration.support.TestFixtures;
import static org.assertj.core.api.Assertions.assertThat;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.context.evaluation.api.events.ApprovalNodePassedEvent;
import com.spe.context.evaluation.api.events.ApprovalRoundStartedEvent;
import com.spe.context.evaluation.api.events.TaskSupplierFinalizedEvent;
import com.spe.context.evaluation.api.events.TaskSupplierRejectedEvent;
import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P4-W2：四个审批链事件 → notify 监听器写通知（05 §5.3、15 §E7、16 §W2.2）。
 *
 * <p>本类直接发布事件，只验证"事件 → 通知"这一段；"谁在什么业务动作里发布"由 W3/W4 的用例覆盖。
 * 每条通知都按**本用例独有的评估单 id / 供应商 id**过滤，不假设全表状态（CLAUDE 默认约定第 5 条）。
 */
class ApprovalEventListenerIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger(1);

  @Autowired private EventPublisher eventPublisher;
  @Autowired private NotificationService notificationService;
  @Autowired private PlatformTransactionManager txManager;

  @Test
  @DisplayName("W2 轮次开始：第一级审批人收到一条待审批通知，且轮次取事件载荷")
  void roundStartedNotifiesTheFirstApprover() {
    long taskSupplierId = nextTaskSupplierId();
    long approver = insertUser("w2-round-approver-" + taskSupplierId, "MANAGER", "PURCHASE");
    long bystander = insertUser("w2-round-bystander-" + taskSupplierId, "EVALUATOR", "QUALITY");

    eventPublisher.publish(new ApprovalRoundStartedEvent(taskSupplierId, approver, 2));

    List<NotificationSummary> received = notificationsAbout(approver, "#" + taskSupplierId + " ");
    assertThat(received).as("审批人必须收到一条（判据 9：行数 ≥ 1）").hasSize(1);
    assertThat(received.get(0).type()).isEqualTo(NotificationService.TYPE_APPROVAL_ROUND_STARTED);
    assertThat(received.get(0).content())
        .as("通知要说明是哪张评估单、第几轮，否则审批人无从下手")
        .contains("评估单 #" + taskSupplierId)
        .contains("第 2 轮")
        .contains("等待你审批");
    assertThat(notificationsAbout(bystander, "#" + taskSupplierId + " "))
        .as("非收件人不应收到这条通知")
        .isEmpty();
  }

  @Test
  @DisplayName("W2 节点通过：下一级审批人收到流转通知")
  void nodePassedNotifiesTheNextApprover() {
    long taskSupplierId = nextTaskSupplierId();
    long nextApprover = insertUser("w2-passed-next-" + taskSupplierId, "MANAGER", "PURCHASE");
    long bystander = insertUser("w2-passed-bystander-" + taskSupplierId, "EVALUATOR", "QUALITY");

    eventPublisher.publish(new ApprovalNodePassedEvent(taskSupplierId, nextApprover));

    List<NotificationSummary> received = notificationsAbout(nextApprover, "#" + taskSupplierId + " ");
    assertThat(received).as("下一级审批人必须收到一条").hasSize(1);
    assertThat(received.get(0).type()).isEqualTo(NotificationService.TYPE_APPROVAL_NODE_PASSED);
    assertThat(received.get(0).content())
        .contains("评估单 #" + taskSupplierId)
        .contains("已通过上一级审批")
        .contains("等待你审批");
    assertThat(notificationsAbout(bystander, "#" + taskSupplierId + " ")).isEmpty();
  }

  @Test
  @DisplayName("W2 驳回：涉及的评估人各一条（同一人去重），意见按 200 字截断")
  void rejectedNotifiesEachEvaluatorOnceWithASummarizedComment() {
    long taskSupplierId = nextTaskSupplierId();
    long evaluatorA = insertUser("w2-reject-a-" + taskSupplierId, "EVALUATOR", "QUALITY");
    long evaluatorB = insertUser("w2-reject-b-" + taskSupplierId, "EVALUATOR", "DELIVERY");
    String longComment = "意".repeat(300);

    eventPublisher.publish(
        new TaskSupplierRejectedEvent(
            taskSupplierId, longComment, List.of(evaluatorA, evaluatorB, evaluatorA)));

    List<NotificationSummary> toA = notificationsAbout(evaluatorA, "#" + taskSupplierId + " ");
    List<NotificationSummary> toB = notificationsAbout(evaluatorB, "#" + taskSupplierId + " ");
    assertThat(toA).as("同一人被重复列入只通知一次").hasSize(1);
    assertThat(toB).hasSize(1);
    assertThat(toA.get(0).type()).isEqualTo(NotificationService.TYPE_TASK_SUPPLIER_REJECTED);
    assertThat(toA.get(0).content())
        .contains("评估单 #" + taskSupplierId)
        .contains("被驳回")
        .contains("审批意见：")
        .contains("…")
        .as("通知内容必须放得进 VARCHAR(500)，且不得整段照抄用户输入")
        .hasSizeLessThan(500)
        .doesNotContain("意".repeat(201));
  }

  @Test
  @DisplayName("W2 定稿：在职采购经理收到生效通知，评估人不是收件人")
  void finalizedNotifiesEnabledManagersOnly() {
    long sequence = nextSequence();
    long taskSupplierId = 9_000_000L + sequence;
    long supplierId = 8_000_000L + sequence;
    int year = 2070 + (int) sequence;
    long manager = insertUser("w2-final-manager-" + sequence, "MANAGER", "PURCHASE");
    long evaluator = insertUser("w2-final-evaluator-" + sequence, "EVALUATOR", "QUALITY");

    eventPublisher.publish(
        new TaskSupplierFinalizedEvent(
            taskSupplierId,
            supplierId,
            year,
            4,
            new BigDecimal("92.50"),
            "A",
            List.of(
                new TaskSupplierFinalizedEvent.DimensionResult(
                    1L, "质量", new BigDecimal("40.00"), new BigDecimal("90.00"), "A")),
            true));

    List<NotificationSummary> toManager =
        notificationsAbout(manager, "供应商 #" + supplierId + " 的 " + year);
    assertThat(toManager).as("采购经理必须收到一条").hasSize(1);
    assertThat(toManager.get(0).type()).isEqualTo(NotificationService.TYPE_TASK_SUPPLIER_FINALIZED);
    assertThat(toManager.get(0).content())
        .as("生效通知要被通知人一眼看懂结果")
        .contains(year + " 年 Q4")
        .contains("总分 92.50")
        .contains("等级 A");
    assertThat(notificationsAbout(evaluator, "供应商 #" + supplierId + " 的 " + year))
        .as("评估人不在「采购经理」收件范围内")
        .isEmpty();
  }

  @Test
  @DisplayName("W2 同事务语义：事件在回滚的事务里发布 ⇒ 通知一并回滚")
  void notificationsRollBackWithThePublishingTransaction() {
    long taskSupplierId = nextTaskSupplierId();
    long approver = insertUser("w2-tx-approver-" + taskSupplierId, "MANAGER", "PURCHASE");

    new TransactionTemplate(txManager)
        .execute(
            status -> {
              eventPublisher.publish(new ApprovalRoundStartedEvent(taskSupplierId, approver, 1));
              assertThat(notificationsAbout(approver, "#" + taskSupplierId + " "))
                  .as("同一事务内应当看得见监听器写的通知")
                  .hasSize(1);
              status.setRollbackOnly();
              return null;
            });

    assertThat(notificationsAbout(approver, "#" + taskSupplierId + " "))
        .as("外层回滚 ⇒ 通知一并消失（给监听器加 REQUIRES_NEW 会让本断言变红）")
        .isEmpty();
  }

  // ---------- 夹具 ----------

  private static long nextSequence() {
    return SEQ.getAndIncrement();
  }

  private static long nextTaskSupplierId() {
    return 9_000_000L + nextSequence();
  }

  private long insertUser(String username, String role, String department) { return TestFixtures.insertUser(jdbcTemplate, username, role, department); }

  /** 本用例自己制造的通知：共享库里同一收件人会有别的通知，按内容里的唯一标记收敛范围。 */
  private List<NotificationSummary> notificationsAbout(long receiverId, String marker) {
    return notificationService.page(receiverId, PageQuery.of(1, 50)).list().stream()
        .filter(summary -> summary.content() != null && summary.content().contains(marker))
        .toList();
  }
}
