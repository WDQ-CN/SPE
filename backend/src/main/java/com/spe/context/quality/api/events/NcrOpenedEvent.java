package com.spe.context.quality.api.events;

import com.spe.common.event.DomainEvent;

/** NCR 开启。 */
public record NcrOpenedEvent(
    long ncrId, long companyId, Long supplierId, long itemId, String lotNo, String defect)
    implements DomainEvent {}
