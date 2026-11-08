package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 维护供应商价格库（MANUAL）。 */
public record CreateSupplierPriceRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotNull(message = "供应商不能为空") Long supplierId,
    @NotNull(message = "物料不能为空") Long itemId,
    @Size(max = 16, message = "单位长度不得超过 16") String uom,
    @DecimalMin(value = "0.0", message = "最小数量不能为负") BigDecimal minQty,
    @NotNull(message = "价格不能为空")
        @DecimalMin(value = "0.00", message = "价格不能为负")
        BigDecimal price,
    Boolean taxIncluded,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @NotNull(message = "生效日不能为空") LocalDate validFrom,
    LocalDate validTo) {}
