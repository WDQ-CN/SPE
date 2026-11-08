package com.spe.context.masterdata.internal.web.dto;

import com.spe.context.masterdata.api.WarehouseType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 编辑仓库；plantId 不允许通过编辑迁移。 */
public record UpdateWarehouseRequest(
    @NotBlank(message = "编码不能为空") @Size(max = 32, message = "编码长度不得超过 32") String code,
    @NotBlank(message = "名称不能为空") @Size(max = 128, message = "名称长度不得超过 128") String name,
    @NotNull(message = "仓库类型不能为空") WarehouseType warehouseType,
    @NotNull(message = "版本不能为空") @Min(value = 0, message = "版本不能为负") Integer version) {}
