package com.spe.context.masterdata.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 新增客户。 */
public record CreateCustomerRequest(
    @NotBlank(message = "编码不能为空") @Size(max = 50, message = "编码长度不得超过 50") String code,
    @NotBlank(message = "名称不能为空") @Size(max = 200, message = "名称长度不得超过 200") String name,
    @Size(max = 64, message = "税号长度不得超过 64") String taxNo,
    @Size(max = 255, message = "地址长度不得超过 255") String address,
    @Size(max = 64, message = "联系人长度不得超过 64") String contactName,
    @Size(max = 32, message = "联系电话长度不得超过 32") String contactPhone,
    @NotBlank(message = "币种不能为空")
        @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母")
        String currency,
    @Size(max = 32, message = "账期长度不得超过 32") String paymentTerms,
    @DecimalMin(value = "0", message = "信用额度不能为负") BigDecimal creditLimit) {}
