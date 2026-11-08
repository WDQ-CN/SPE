package com.spe.context.quality.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** NCR 投影。 */
public record NcrView(
    long id,
    String ncrNo,
    long companyId,
    String sourceType,
    Long sourceId,
    Long inspectionId,
    long itemId,
    Long supplierId,
    String lotNo,
    BigDecimal qty,
    String defect,
    NcrStatus status,
    NcrDisposition disposition,
    String reason,
    LocalDateTime createdAt,
    LocalDateTime dispositionedAt,
    LocalDateTime closedAt) {}
