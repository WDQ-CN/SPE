package com.spe.context.inventory.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 创建调拨单。 */
public record CreateTransferRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotNull(message = "源仓库不能为空") Long fromWarehouseId,
    Long fromLocationId,
    @NotNull(message = "目标仓库不能为空") Long toWarehouseId,
    Long toLocationId,
    @Size(max = 255, message = "原因长度不得超过 255") String reason,
    @NotEmpty(message = "调拨至少需要一行") @Valid List<CreateTransferLineRequest> lines) {}
