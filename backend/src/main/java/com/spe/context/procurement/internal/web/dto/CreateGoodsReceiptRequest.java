package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** 收货过账。 */
public record CreateGoodsReceiptRequest(
    @Positive(message = "采购订单 id 必须为正") long poId,
    @Positive(message = "仓库 id 必须为正") long warehouseId,
    @NotEmpty(message = "至少需要一行收货明细") List<@Valid GoodsReceiptLineRequest> lines) {}
