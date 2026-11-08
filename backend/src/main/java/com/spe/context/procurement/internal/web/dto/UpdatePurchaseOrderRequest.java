package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 修改采购订单（仅 DRAFT）。 */
public record UpdatePurchaseOrderRequest(
    @Positive(message = "供应商 id 必须为正") long supplierId,
    @Positive(message = "工厂 id 必须为正") long plantId,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @Size(max = 32, message = "付款条件长度不得超过 32") String paymentTerms,
    @NotEmpty(message = "至少需要一行订单明细") List<@Valid PurchaseOrderLineRequest> lines,
    @PositiveOrZero(message = "版本号不能为负") int version) {}
