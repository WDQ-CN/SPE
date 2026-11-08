package com.spe.context.procurement.internal;

import java.time.LocalDateTime;
import java.util.List;

/** 门户 RFQ 详情：只含被邀请供应商可见的字段。 */
public record PortalRfqView(
    long rfqId,
    String rfqNo,
    String title,
    String currency,
    LocalDateTime deadlineAt,
    boolean sealed,
    SourcingRfqStatus status,
    InvitationStatus invitationStatus,
    List<SourcingRfqLineView> lines,
    SupplierQuoteView myQuote) {}
