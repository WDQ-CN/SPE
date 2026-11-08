package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 收货行：数量 + 批次/库位（批次管理物料必须带 lotNo）。 */
public record GoodsReceiptLineRequest(
    @Positive(message = "订单行 id 必须为正") long poLineId,
    @NotNull(message = "收货数量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "收货数量必须大于 0")
        @Digits(integer = 13, fraction = 6, message = "数量精度不得超过 6 位小数")
        BigDecimal qty,
    @Size(max = 64, message = "批次号长度不得超过 64") String lotNo,
    LocalDate productionDate,
    LocalDate expiryDate,
    @Positive(message = "库位 id 必须为正") Long locationId,
    @Size(max = 100, message = "序列号数量不得超过 100") List<@Size(max = 64, message = "序列号长度不得超过 64") String> serials) {}
