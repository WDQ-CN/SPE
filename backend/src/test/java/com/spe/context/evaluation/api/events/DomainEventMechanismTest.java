package com.spe.context.evaluation.api.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spe.common.event.EventPublisher;
import com.spe.integration.AbstractIntegrationTest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 领域事件机制的**证明**（13 §E15 / 14 §W1.4）。本项目自 P0 起就有 EventPublisher，但**从未有使用者**；
 * 而 05 §5.5 T3「定稿的多表写在同一事务」与 ADR-9 都建立在「监听器同步执行于发布方事务内、
 * 失败随主事务回滚」这个**前提**上。P3 先把这个前提证出来，再写业务监听器（W3/W6）。
 *
 * <p>三条断言（14 §W1.4）：① 事件真的被消费；② 监听器的写入与发布方同事务；③ 监听器抛异常 ⇒ 发布方回滚。
 *
 * <p><b>探针为什么留在 src/test</b>：14 §W1.4 说「证明后删掉探针」，指的是**不要在生产代码里留一个
 * 什么都不做的监听器**（CLAUDE 第 1 条）。这里的探针是**测试作用域**的 bean（生产侧至今一个监听器都没有），
 * 它换来的是这套机制本身的**回归守卫**：将来谁把 EventPublisher 改成异步（或给监听器加 @Async），
 * 这三条断言会立刻变红——那正是 P4 定稿事务最怕的静默退化。真实监听器在 W3/W6（notify 最小版）落地。
 */
@Import(DomainEventMechanismTest.ProbeConfig.class)
class DomainEventMechanismTest extends AbstractIntegrationTest {

  private static final String PROBE_TITLE = "W1.4-事件机制探针";
  private static final String PROBE_TYPE = "TASK_PUBLISHED";
  private static final String PROBE_FAILURE_MESSAGE = "探针故意失败：验证监听器异常会让发布方回滚";
  private static final long PROBE_RECEIVER_ID = 1L;
  private static final long TASK_ID = 4242L;
  private static final long TASK_SUPPLIER_ID = 8888L;

  @Autowired private EventPublisher eventPublisher;
  @Autowired private PlatformTransactionManager txManager;
  @Autowired private EventProbe probe;

  private TransactionTemplate txTemplate;

  @BeforeEach
  void resetProbe() {
    probe.reset();
    txTemplate = new TransactionTemplate(txManager);
    deleteProbeRows();
  }

  @AfterEach
  void cleanProbeRows() {
    deleteProbeRows();
  }

  @Test
  @DisplayName("① 发布真的会到达监听器：两个事件各被消费一次，载荷原样传递")
  void publishedEventsReachListeners() {
    eventPublisher.publish(sampleTaskPublished());
    eventPublisher.publish(new ApprovalRoundStartedEvent(TASK_SUPPLIER_ID, 7L, 1));

    assertThat(probe.taskPublishedCount()).as("TaskPublishedEvent 被消费的次数").isEqualTo(1);
    assertThat(probe.lastTaskId()).as("载荷里的 taskId 原样传递").isEqualTo(TASK_ID);
    assertThat(probe.approvalRoundCount()).as("ApprovalRoundStartedEvent 被消费的次数").isEqualTo(1);
    assertThat(probe.lastTaskSupplierId()).as("载荷里的 taskSupplierId 原样传递").isEqualTo(TASK_SUPPLIER_ID);
    assertThat(probeRows())
        .as("监听器里的写入在无事务包裹时立即生效（两次事件 ⇒ 两行）")
        .isEqualTo(2L);
  }

  @Test
  @DisplayName("② 监听器与发布方同事务：发布方回滚 ⇒ 监听器写的行一并消失")
  void listenerWriteIsInsideThePublisherTransaction() {
    var tx = txManager.getTransaction(new org.springframework.transaction.support.DefaultTransactionDefinition());
    eventPublisher.publish(sampleTaskPublished());
    assertThat(probeRows()).as("同一事务里应当看得见监听器写的行").isEqualTo(1L);
    txManager.rollback(tx);

    assertThat(probeRows()).as("发布方回滚之后，监听器写的行必须一并消失").isZero();
  }

  @Test
  @DisplayName("③ 监听器抛异常 ⇒ 异常穿透回发布方且事务整体回滚（同步语义）")
  void listenerFailureRollsBackThePublisherTransaction() {
    probe.setExplode(true);
    try {
      assertThatThrownBy(
              () ->
                  txTemplate.execute(
                      status -> {
                        jdbcTemplate.update(
                            "insert into notification (receiver_id, type, title, content) values (?, ?, ?, ?)",
                            PROBE_RECEIVER_ID, PROBE_TYPE, PROBE_TITLE, "发布方自己的写入");
                        eventPublisher.publish(sampleTaskPublished());
                        return null;
                      }))
          .as("监听器的异常必须原样抛回发布方（同步执行 ⇒ 发布方看得见失败）")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(PROBE_FAILURE_MESSAGE);
    } finally {
      probe.setExplode(false);
    }

    assertThat(probeRows()).as("发布方自己的写入也必须随事务回滚消失").isZero();
  }

  private void deleteProbeRows() {
    jdbcTemplate.update("delete from notification where title = ?", PROBE_TITLE);
  }

  private long probeRows() {
    Long rows =
        jdbcTemplate.queryForObject(
            "select count(*) from notification where title = ?", Long.class, PROBE_TITLE);
    return rows == null ? -1L : rows;
  }

  private static TaskPublishedEvent sampleTaskPublished() {
    return new TaskPublishedEvent(
        TASK_ID,
        "2026 年 Q4 供应商评估",
        2026,
        4,
        List.of(
            new TaskPublishedEvent.SheetAssignment(11L, 7L),
            new TaskPublishedEvent.SheetAssignment(12L, null)));
  }

  /** 事件机制探针（测试作用域）：计数 + 由开关控制的副作用（写一行 / 抛异常）。 */
  static class EventProbe {

    private final AtomicInteger taskPublishedCount = new AtomicInteger();
    private final AtomicInteger approvalRoundCount = new AtomicInteger();
    private volatile Long lastTaskId;
    private volatile Long lastTaskSupplierId;
    private volatile boolean writeMarkerRow = true;
    private volatile boolean explode;
    private final JdbcTemplate jdbcTemplate;

    EventProbe(JdbcTemplate jdbcTemplate) {
      this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener
    void onTaskPublished(TaskPublishedEvent event) {
      taskPublishedCount.incrementAndGet();
      lastTaskId = event.taskId();
      react();
    }

    @EventListener
    void onApprovalRoundStarted(ApprovalRoundStartedEvent event) {
      approvalRoundCount.incrementAndGet();
      lastTaskSupplierId = event.taskSupplierId();
      react();
    }

    private void react() {
      if (explode) {
        throw new IllegalStateException(PROBE_FAILURE_MESSAGE);
      }
      if (writeMarkerRow) {
        jdbcTemplate.update(
            "insert into notification (receiver_id, type, title, content) values (?, ?, ?, ?)",
            PROBE_RECEIVER_ID, PROBE_TYPE, PROBE_TITLE, "W1.4 事件机制探针写入");
      }
    }

    int taskPublishedCount() {
      return taskPublishedCount.get();
    }

    int approvalRoundCount() {
      return approvalRoundCount.get();
    }

    Long lastTaskId() {
      return lastTaskId;
    }

    Long lastTaskSupplierId() {
      return lastTaskSupplierId;
    }

    void setExplode(boolean explode) {
      this.explode = explode;
    }

    void reset() {
      taskPublishedCount.set(0);
      approvalRoundCount.set(0);
      lastTaskId = null;
      lastTaskSupplierId = null;
      writeMarkerRow = true;
      explode = false;
    }
  }

  /**
   * 探针 bean。⚠️ 监听方法用 {@code @EventListener} 而**不能**写成
   * {@code ApplicationListener<TaskPublishedEvent>}：Spring 的 {@code ApplicationListener<E extends ApplicationEvent>}
   * 有上界，而本项目的领域事件是普通 record（只实现 {@code DomainEvent}）——写后者会**编译不过**。
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfig {

    @Bean
    EventProbe eventProbe(JdbcTemplate jdbcTemplate) {
      return new EventProbe(jdbcTemplate);
    }
  }
}
