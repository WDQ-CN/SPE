package com.spe.context.supplier.internal.web.dto;

import com.spe.context.supplier.api.SupplierStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 启停供应商入参（01 S2 / 02 §4 `PUT /suppliers/{id}/status`）。
 *
 * <p>只传**目标状态**：前置状态由后端按两态机推出（{@code SupplierStatus.other()}），
 * 客户端无法伪造「从哪来」——这正是守卫 UPDATE 需要的条件。
 */
public record ChangeSupplierStatusRequest(
    @NotNull(message = "目标状态不能为空") SupplierStatus status,
    @NotNull(message = "版本号不能为空") @Min(value = 0, message = "版本号不能为负") Integer version) {}
