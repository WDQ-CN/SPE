package com.spe.context.alert.api.events;

import com.spe.common.event.DomainEvent;

/**
 * 一条预警已生成（M5 W2；由定稿/重算的监听器在**同一事务**内发布）。
 *
 * <p>消费方：notify（给全部在职 MANAGER 写站内通知）。载荷带 `supplierName`/`title`/`content`，
 * 让 notify 不需要反向读 alert 或 supplier（写各自拥有的表，05 §5.1）。
 */
public record AlertCreatedEvent(
    long alertId,
    long taskSupplierId,
    long supplierId,
    String supplierName,
    int year,
    int quarter,
    String type,
    String title,
    String content)
    implements DomainEvent {}
