package com.spe.context.masterdata.internal.web.dto;

import com.spe.context.masterdata.api.AccountType;
import com.spe.context.masterdata.api.NormalBalance;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 新增会计科目。 */
public record CreateGlAccountRequest(
    @NotNull(message = "公司不能为空") @Positive(message = "公司不合法") Long companyId,
    @NotBlank(message = "科目编码不能为空") @Size(max = 32, message = "科目编码长度不得超过 32") String code,
    @NotBlank(message = "科目名称不能为空") @Size(max = 128, message = "科目名称长度不得超过 128") String name,
    @NotNull(message = "科目类型不能为空") AccountType accountType,
    @NotNull(message = "余额方向不能为空") NormalBalance normalBalance,
    Boolean requiresCostCenter) {}
