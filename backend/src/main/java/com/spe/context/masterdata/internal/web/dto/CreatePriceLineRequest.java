package com.spe.context.masterdata.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 新增价格表行。 */
public record CreatePriceLineRequest(
    @NotNull(message = "物料不能为空") @Positive(message = "物料不合法") Long itemId,
    @NotBlank(message = "计量单位不能为空") @Size(max = 16, message = "计量单位长度不得超过 16") String uom,
    @NotNull(message = "数量阶梯不能为空")
        @DecimalMin(value = "0", message = "数量阶梯不能为负")
        BigDecimal minQty,
    @NotNull(message = "单价不能为空")
        @DecimalMin(value = "0", message = "单价不能为负")
        BigDecimal price,
    Boolean taxIncluded,
    @NotNull(message = "生效日期不能为空") LocalDate validFrom,
    LocalDate validTo) {}
