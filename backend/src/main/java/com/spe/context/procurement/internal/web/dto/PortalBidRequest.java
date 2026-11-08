package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 门户出价。 */
public record PortalBidRequest(
    @NotNull(message = "出价金额不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "出价金额必须大于 0")
        @Digits(integer = 15, fraction = 4, message = "金额精度不得超过 4 位小数")
        BigDecimal amount,
    @NotBlank(message = "幂等键不能为空") @Size(max = 64, message = "幂等键长度不得超过 64")
        String idempotencyKey) {}
