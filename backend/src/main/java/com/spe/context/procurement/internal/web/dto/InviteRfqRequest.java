package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 邀请供应商。 */
public record InviteRfqRequest(
    @NotEmpty(message = "至少需要邀请一家供应商") List<Long> supplierIds) {}
