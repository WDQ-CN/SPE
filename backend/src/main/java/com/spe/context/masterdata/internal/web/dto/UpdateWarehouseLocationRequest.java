package com.spe.context.masterdata.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 编辑库位。 */
public record UpdateWarehouseLocationRequest(
    @NotBlank(message = "编码不能为空") @Size(max = 32, message = "编码长度不得超过 32") String code,
    @NotBlank(message = "名称不能为空") @Size(max = 64, message = "名称长度不得超过 64") String name,
    @Size(max = 32, message = "区域长度不得超过 32") String zone,
    @Size(max = 32, message = "巷道长度不得超过 32") String aisle,
    @Size(max = 32, message = "货架长度不得超过 32") String rack,
    @Size(max = 32, message = "层长度不得超过 32") String levelNo,
    @Size(max = 32, message = "格长度不得超过 32") String binNo,
    @PositiveOrZero(message = "版本号不能为负") int version) {}
