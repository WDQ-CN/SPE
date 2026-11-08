package com.spe.context.inventory.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 批次状态变更。 */
public record ChangeLotStatusRequest(
    @NotBlank(message = "状态不能为空") @Size(max = 16, message = "状态长度不得超过 16") String status,
    @PositiveOrZero(message = "版本号不能为负") int version) {}
