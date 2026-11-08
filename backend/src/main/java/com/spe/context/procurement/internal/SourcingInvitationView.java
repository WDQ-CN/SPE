package com.spe.context.procurement.internal;

import java.time.LocalDateTime;

/** 询价邀请投影。 */
public record SourcingInvitationView(
    long id,
    long supplierId,
    String supplierCode,
    String supplierName,
    InvitationStatus status,
    LocalDateTime invitedAt,
    LocalDateTime respondedAt) {}
