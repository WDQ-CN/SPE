package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.RequisitionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 请购单详情投影。 */
public record RequisitionView(
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
    LocalDateTime createdAt,
    List<RequisitionLineView> lines) {}
