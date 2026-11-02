package com.spe.context.alert.api.events;

import com.spe.common.event.DomainEvent;
import java.time.LocalDate;

/**
 * 整改任务已创建/已指派（W4；由 `CorrectiveActionService.create` 在同一事务内发布）。
 *
 * <p>消费方：notify（给负责人写一条站内通知）。载荷带 `supplierName` 与 `dueDate`，
 * 让 notify 不必反查 alert/supplier（与 {@link AlertCreatedEvent} 同一口径）。
 */
public record CorrectiveActionCreatedEvent(
    long actionId,
    long alertId,
    long supplierId,
    String supplierName,
    long ownerId,
    LocalDate dueDate)
    implements DomainEvent {}
