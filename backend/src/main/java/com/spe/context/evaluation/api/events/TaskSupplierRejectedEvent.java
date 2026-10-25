package com.spe.context.evaluation.api.events;

import com.spe.common.event.DomainEvent;
import java.util.List;

/**
 * 评估单被驳回（05 §5.3 事件契约表：{@code taskSupplierId、驳回意见、涉及评估人} → notify 通知评估人）。
 *
 * <p>发布点：05 §5.5 T5「驳回」——评估单守卫迁移回 {@code SCORING}、轮次 +1、全部已提交评分单解锁后发布。
 * **解锁本身不走事件**（05 §5.3 明写），事件只承载"被驳回"这个事实。
 *
 * <p>载荷为不可变 record（05 §5.3、ArchUnit R-D）；{@code evaluatorIds} 在构造时拷贝一次，
 * 避免发布方之后改动集合影响已经发出的事件。
 *
 * @param comment 驳回意见（服务层已判必填；监听器写通知时按 06 §2.8 的 200 字上限截断）
 * @param evaluatorIds 涉及本评估单的评估人（监听器按人去重后各写一条通知）
 */
public record TaskSupplierRejectedEvent(long taskSupplierId, String comment, List<Long> evaluatorIds)
    implements DomainEvent {

  public TaskSupplierRejectedEvent {
    evaluatorIds = List.copyOf(evaluatorIds);
  }
}
