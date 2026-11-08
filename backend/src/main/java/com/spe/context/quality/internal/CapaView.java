package com.spe.context.quality.internal;

import java.time.LocalDateTime;

/** CAPA 投影。 */
public record CapaView(
    long id,
    String capaNo,
    long companyId,
    Long ncrId,
    Long supplierId,
    String rootCause,
    String actionPlan,
    CapaStatus status,
    LocalDateTime createdAt,
    Long verifiedBy,
    LocalDateTime verifiedAt,
    LocalDateTime closedAt) {}
