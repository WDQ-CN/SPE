package com.spe.context.masterdata.internal.web.dto;

import com.spe.context.masterdata.api.MasterDataStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 主数据启停入参。 */
public record ChangeMasterDataStatusRequest(
    @NotNull(message = "状态不能为空") MasterDataStatus status,
    @NotNull(message = "版本不能为空") @Min(value = 0, message = "版本不能为负") Integer version) {}
