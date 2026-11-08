package com.spe.context.quality.api.events;

import com.spe.common.event.DomainEvent;

/** 检验完成（PASSED/FAILED/DEVIATED）。 */
public record InspectionCompletedEvent(
    long inspectionId,
    long companyId,
    long itemId,
    Long supplierId,
    String lotNo,
    String status)
    implements DomainEvent {}
