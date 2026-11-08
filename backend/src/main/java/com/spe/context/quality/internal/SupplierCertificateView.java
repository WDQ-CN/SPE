package com.spe.context.quality.internal;

import java.time.LocalDate;

/** 证书投影；effectiveStatus 由有效期实时计算。 */
public record SupplierCertificateView(
    long id,
    long companyId,
    long supplierId,
    Long itemId,
    String certType,
    String certNo,
    LocalDate validFrom,
    LocalDate validTo,
    CertificateStatus status,
    String effectiveStatus,
    long daysUntilExpiry) {}
