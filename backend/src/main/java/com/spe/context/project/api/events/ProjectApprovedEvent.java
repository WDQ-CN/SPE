package com.spe.context.project.api.events;

import com.spe.common.event.DomainEvent;

/** 项目已批准。 */
public record ProjectApprovedEvent(long projectId, long companyId, String projectNo)
    implements DomainEvent {}
