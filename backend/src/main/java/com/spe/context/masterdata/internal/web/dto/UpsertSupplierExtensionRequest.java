package com.spe.context.masterdata.internal.web.dto;

import com.spe.context.masterdata.api.QualificationStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 供应商扩展新增/更新（1:1）；version=0 表示新记录。 */
public record UpsertSupplierExtensionRequest(
    @Size(max = 64, message = "税号长度不得超过 64") String taxNo,
    @Size(max = 64, message = "银行账号长度不得超过 64") String bankAccount,
    @Size(max = 128, message = "开户行长度不得超过 128") String bankName,
    @Size(max = 32, message = "付款条件长度不得超过 32") String paymentTerms,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @Size(max = 16, message = "贸易术语长度不得超过 16") String incoterms,
    @NotNull(message = "准入状态不能为空") QualificationStatus qualificationStatus,
    LocalDate certificateExpiry,
    @NotNull(message = "版本不能为空") @Min(value = 0, message = "版本不能为负") Integer version) {}
