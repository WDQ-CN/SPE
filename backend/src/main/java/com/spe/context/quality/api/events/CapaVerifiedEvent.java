package com.spe.context.quality.api.events;

import com.spe.common.event.DomainEvent;

/** CAPA 有效性已验证。 */
public record CapaVerifiedEvent(long capaId, long companyId, Long supplierId)
    implements DomainEvent {}
