package com.spe.context.masterdata.internal.web.dto;

import com.spe.context.masterdata.api.AccountType;
import com.spe.context.masterdata.api.NormalBalance;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 编辑会计科目；companyId 不可迁移。 */
public record UpdateGlAccountRequest(
    @NotBlank(message = "科目编码不能为空") @Size(max = 32, message = "科目编码长度不得超过 32") String code,
    @NotBlank(message = "科目名称不能为空") @Size(max = 128, message = "科目名称长度不得超过 128") String name,
    @NotNull(message = "科目类型不能为空") AccountType accountType,
    @NotNull(message = "余额方向不能为空") NormalBalance normalBalance,
    Boolean requiresCostCenter,
    @NotNull(message = "版本不能为空") @Min(value = 0, message = "版本不能为负") Integer version) {}
