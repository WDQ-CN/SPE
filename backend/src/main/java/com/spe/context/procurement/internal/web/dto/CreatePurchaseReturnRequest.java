package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** 创建采购退货。 */
public record CreatePurchaseReturnRequest(
    @Positive(message = "采购订单 id 必须为正") long poId,
    @Positive(message = "仓库 id 必须为正") long warehouseId,
    @NotNull(message = "退货日期不能为空") LocalDate returnDate,
    @NotBlank(message = "退货原因不能为空")
        @Size(max = 200, message = "退货原因长度不得超过 200")
        String reason,
    @NotEmpty(message = "至少需要一行退货明细") List<@Valid PurchaseReturnLineRequest> lines) {}
