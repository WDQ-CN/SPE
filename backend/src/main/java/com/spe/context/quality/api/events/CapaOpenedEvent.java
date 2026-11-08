package com.spe.context.quality.api.events;

import com.spe.common.event.DomainEvent;

/** CAPA 开启。 */
public record CapaOpenedEvent(long capaId, long companyId, Long supplierId) implements DomainEvent {}
