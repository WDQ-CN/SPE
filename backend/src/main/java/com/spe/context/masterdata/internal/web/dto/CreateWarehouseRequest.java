package com.spe.context.masterdata.internal.web.dto;

import com.spe.context.masterdata.api.WarehouseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 新增仓库。 */
public record CreateWarehouseRequest(
    @NotNull(message = "所属工厂不能为空") @Positive(message = "所属工厂不合法") Long plantId,
    @NotBlank(message = "编码不能为空") @Size(max = 32, message = "编码长度不得超过 32") String code,
    @NotBlank(message = "名称不能为空") @Size(max = 128, message = "名称长度不得超过 128") String name,
    @NotNull(message = "仓库类型不能为空") WarehouseType warehouseType) {}
