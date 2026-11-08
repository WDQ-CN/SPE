package com.spe.context.masterdata.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 编辑工厂；companyId 不允许通过编辑迁移，避免历史交易归属漂移。 */
public record UpdatePlantRequest(
    @NotBlank(message = "编码不能为空") @Size(max = 32, message = "编码长度不得超过 32") String code,
    @NotBlank(message = "名称不能为空") @Size(max = 128, message = "名称长度不得超过 128") String name,
    @Size(max = 255, message = "地址长度不得超过 255") String address,
    @NotNull(message = "版本不能为空") @Min(value = 0, message = "版本不能为负") Integer version) {}
