package com.spe.context.masterdata.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 新增工厂。 */
public record CreatePlantRequest(
    @NotNull(message = "所属公司不能为空") @Positive(message = "所属公司不合法") Long companyId,
    @NotBlank(message = "编码不能为空") @Size(max = 32, message = "编码长度不得超过 32") String code,
    @NotBlank(message = "名称不能为空") @Size(max = 128, message = "名称长度不得超过 128") String name,
    @Size(max = 255, message = "地址长度不得超过 255") String address) {}
