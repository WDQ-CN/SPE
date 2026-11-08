package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** 付款申请。 */
public record CreatePaymentRequest(
    @Positive(message = "发票 id 必须为正") long invoiceId,
    @NotNull(message = "付款金额不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "付款金额必须大于 0")
        @Digits(integer = 15, fraction = 4, message = "金额精度不得超过 4 位小数")
        BigDecimal amount) {}
