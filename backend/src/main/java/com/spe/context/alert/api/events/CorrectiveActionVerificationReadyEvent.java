package com.spe.context.alert.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;

/**
 * 整改的下期验证数据已回填（W4；由 `CorrectiveActionVerifier.fill` 在成功写入后发布）。
 *
 * <p>消费方：notify（通知负责人与创建人）。`verifyScore` 在 PENDING（同周期多份/维度缺失）时为 null，
 * `verifyOutcome` 仍为 PENDING；通知内容会明确“待人工判断”。
 */
public record CorrectiveActionVerificationReadyEvent(
    long actionId,
    long alertId,
    long supplierId,
    String supplierName,
    long ownerId,
    long createdBy,
    int verifyYear,
    int verifyQuarter,
    BigDecimal verifyScore,
    String verifyOutcome)
    implements DomainEvent {}
