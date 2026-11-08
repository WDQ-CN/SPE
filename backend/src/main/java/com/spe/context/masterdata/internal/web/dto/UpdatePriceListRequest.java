package com.spe.context.masterdata.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 编辑价格表（类型不可改）。 */
public record UpdatePriceListRequest(
    @NotBlank(message = "编码不能为空") @Size(max = 50, message = "编码长度不得超过 50") String code,
    @NotBlank(message = "币种不能为空")
        @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母")
        String currency,
    @NotNull(message = "生效日期不能为空") LocalDate validFrom,
    LocalDate validTo,
    @NotNull(message = "版本不能为空") @Min(value = 0, message = "版本不能为负") Integer version) {}
