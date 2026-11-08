package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.RequisitionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 请购单列表投影（不含行）。 */
public record RequisitionSummaryView(
    long id,
    String docNo,
    long companyId,
    String companyName,
    long requesterId,
    String requesterName,
    LocalDate needDate,
    String currency,
    BigDecimal amount,
    RequisitionStatus status,
    int version,
    LocalDateTime createdAt) {}
