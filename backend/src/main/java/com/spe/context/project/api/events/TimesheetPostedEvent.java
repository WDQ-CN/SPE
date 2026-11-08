package com.spe.context.project.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;

/** 工时已过账进项目成本。 */
public record TimesheetPostedEvent(
    long timesheetId, long companyId, long userId, BigDecimal totalAmount, String currency)
    implements DomainEvent {}
