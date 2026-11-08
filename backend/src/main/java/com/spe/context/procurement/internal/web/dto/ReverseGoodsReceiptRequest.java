package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 收货冲销：必须说明原因。 */
public record ReverseGoodsReceiptRequest(
    @NotBlank(message = "冲销原因不能为空")
        @Size(max = 200, message = "冲销原因长度不得超过 200")
        String reason,
    @PositiveOrZero(message = "版本号不能为负") int version) {}
