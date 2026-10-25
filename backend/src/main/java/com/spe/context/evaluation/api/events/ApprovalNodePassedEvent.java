package com.spe.context.evaluation.api.events;

import com.spe.common.event.DomainEvent;

/**
 * 审批节点通过（05 §5.3 事件契约表：{@code taskSupplierId、下一级审批人} → notify 通知下一审批人）。
 *
 * <p>发布点：05 §5.5 T5「审批通过」的**非末级**分支——评估单守卫迁移到 {@code APPROVING}、
 * {@code current_node} 推进后发布；末级通过不走本事件，而是直接进入 T3 定稿（15 §E5，避免"先生效再定稿"）。
 *
 * <p>载荷为不可变 record（05 §5.3、ArchUnit R-D）。
 *
 * @param nextApproverId 下一级审批人（发布时由审批链按 {@code current_node} 解析；非末级必然存在）
 */
public record ApprovalNodePassedEvent(long taskSupplierId, long nextApproverId)
    implements DomainEvent {}
