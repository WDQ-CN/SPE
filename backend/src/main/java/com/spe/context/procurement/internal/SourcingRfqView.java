package com.spe.context.procurement.internal;

import java.time.LocalDateTime;
import java.util.List;

/** RFQ 详情投影。 */
public record SourcingRfqView(
    long id,
    String rfqNo,
    long companyId,
    long plantId,
    long buyerId,
    String title,
    String currency,
    LocalDateTime deadlineAt,
    boolean sealed,
    SourcingRfqStatus status,
    Long sourcePrId,
    AwardPolicy awardPolicy,
    int version,
    LocalDateTime createdAt,
    LocalDateTime sentAt,
    LocalDateTime closedAt,
    List<SourcingRfqLineView> lines,
    List<SourcingInvitationView> invitations) {}
