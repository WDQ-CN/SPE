package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 发票批准：异常匹配必须带原因，正常匹配意见可选。 */
public record ApproveInvoiceRequest(
    @Size(max = 500, message = "意见长度不得超过 500") String comment,
    @PositiveOrZero(message = "版本号不能为负") int version) {}
