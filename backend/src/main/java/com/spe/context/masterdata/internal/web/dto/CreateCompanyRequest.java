package com.spe.context.masterdata.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新增公司。 */
public record CreateCompanyRequest(
    @NotBlank(message = "编码不能为空") @Size(max = 32, message = "编码长度不得超过 32") String code,
    @NotBlank(message = "名称不能为空") @Size(max = 128, message = "名称长度不得超过 128") String name,
    @NotBlank(message = "本位币不能为空")
        @Pattern(regexp = "[A-Za-z]{3}", message = "本位币必须为 3 位字母")
        String baseCurrency,
    @Size(max = 64, message = "税号长度不得超过 64") String taxNo,
    @NotBlank(message = "国家不能为空")
        @Pattern(regexp = "[A-Za-z]{2}", message = "国家必须为 2 位字母")
        String country) {}
