package com.spe.context.masterdata.internal.web.dto;

import com.spe.context.masterdata.api.TaxType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 新增税码。 */
public record CreateTaxCodeRequest(
    @NotBlank(message = "编码不能为空") @Size(max = 32, message = "编码长度不得超过 32") String code,
    @NotBlank(message = "国家不能为空")
        @Pattern(regexp = "[A-Za-z]{2}", message = "国家必须为 2 位字母")
        String country,
    @NotNull(message = "税率不能为空")
        @DecimalMin(value = "0", message = "税率不能为负")
        @DecimalMax(value = "1", message = "税率不能大于 1")
        BigDecimal rate,
    @NotNull(message = "税种方向不能为空") TaxType taxType,
    @NotNull(message = "生效日期不能为空") LocalDate effectiveFrom) {}
