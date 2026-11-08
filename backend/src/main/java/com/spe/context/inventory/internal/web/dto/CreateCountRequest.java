package com.spe.context.inventory.internal.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 创建盘点单。 */
public record CreateCountRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotNull(message = "仓库不能为空") Long warehouseId,
    Long itemId,
    Long lotId,
    Long locationId,
    @Size(max = 255, message = "盘点原因长度不得超过 255") String reason) {}
