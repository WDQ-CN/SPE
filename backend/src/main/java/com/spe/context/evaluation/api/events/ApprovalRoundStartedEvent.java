package com.spe.context.evaluation.api.events;

import com.spe.common.event.DomainEvent;

/**
 * 审批轮次开始（05 §5.3 事件契约表：{@code taskSupplierId、第一级审批人、轮次} → notify 通知审批人）。
 *
 * <p>发布点：05 §5.5 T2「提交评分单」——最后一张评分单提交时，评估单守卫迁移到「待审批」后发布。
 *
 * <p>⚠️ P3 <b>有发布方、没有消费者</b>：审批与通知审批人属 P4（07 §31），本阶段监听器尚未落地——
 * 这不是遗漏，05 §5.3 已登记（13 §8 的待补清单第 9 项）。载荷为不可变 record（ArchUnit R-D）。
 *
 * @param round 审批轮次，从 1 起；驳回后重提时递增（01 P2「重提后从第一级重审」）
 */
public record ApprovalRoundStartedEvent(long taskSupplierId, long approverId, int round)
    implements DomainEvent {}
