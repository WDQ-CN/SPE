package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;

/** 门户提交报价。 */
public record PortalQuoteRequest(
    LocalDate validUntil,
    @NotEmpty(message = "报价至少需要一行") @Valid List<PortalQuoteLineRequest> lines) {}
