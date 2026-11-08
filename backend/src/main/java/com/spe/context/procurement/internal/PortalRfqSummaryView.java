package com.spe.context.procurement.internal;

import java.time.LocalDateTime;

/** 门户 RFQ 列表项：不包含其他供应商与报价信息。 */
public record PortalRfqSummaryView(
    long rfqId,
    String rfqNo,
    String title,
    String currency,
    LocalDateTime deadlineAt,
    SourcingRfqStatus status,
    InvitationStatus invitationStatus,
    SupplierQuoteStatus myQuoteStatus) {}
