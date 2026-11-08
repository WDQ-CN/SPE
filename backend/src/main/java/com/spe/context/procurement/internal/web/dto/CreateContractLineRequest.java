package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 合同行创建参数。 */
public record CreateContractLineRequest(
    @NotNull(message = "物料不能为空") Long itemId,
    @Size(max = 16, message = "单位长度不得超过 16") String uom,
    @NotNull(message = "单价不能为空")
        @DecimalMin(value = "0.00", message = "单价不能为负")
        BigDecimal price,
    @DecimalMin(value = "0.0", message = "最小数量不能为负") BigDecimal minQty,
    @DecimalMin(value = "0.0", message = "最大数量不能为负") BigDecimal maxQty,
    Boolean taxIncluded,
    LocalDate validFrom,
    LocalDate validTo) {}
