package com.spe.context.evaluation.api.events;

import com.spe.common.event.DomainEvent;
import java.util.List;

/**
 * 任务已发布（05 §5.3 事件契约表：{@code taskId、名称、周期、指派清单} → notify 通知被指派评估人）。
 *
 * <p>发布点：05 §5.5 T1「发布任务」——单事务里批量生成评估单/评分单并完成自动指派后发布（01 J2）。
 * 监听器同步执行于发布方事务内（05 ADR-9）：监听器失败会让发布整体回滚。
 *
 * <p>载荷为不可变 record（05 §5.3、ArchUnit R-D）；`assignments` 在构造时拷贝一次，
 * 保证发布之后调用方手里的集合再改动也影响不到事件。
 */
public record TaskPublishedEvent(
    long taskId, String taskName, int year, int quarter, List<SheetAssignment> assignments)
    implements DomainEvent {

  public TaskPublishedEvent {
    assignments = List.copyOf(assignments);
  }

  /**
   * 一张评分单的指派结果。
   *
   * <p>`assigneeId` 为 `null` 表示「待指派」（模板维度的建议部门下没有在职评估人，
   * 13 §E12）——监听器应跳过它（没有人可通知），而不是写一条收件人为空的站内通知。
   */
  public record SheetAssignment(long sheetId, Long assigneeId) {}
}
