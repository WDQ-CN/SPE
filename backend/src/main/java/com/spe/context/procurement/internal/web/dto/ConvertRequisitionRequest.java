package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 请购单转 PO。 */
public record ConvertRequisitionRequest(
    @Positive(message = "供应商 id 必须为正") long supplierId,
    @Positive(message = "工厂 id 必须为正") long plantId,
    @Size(max = 32, message = "付款条件长度不得超过 32") String paymentTerms,
    @PositiveOrZero(message = "版本号不能为负") int version) {}
