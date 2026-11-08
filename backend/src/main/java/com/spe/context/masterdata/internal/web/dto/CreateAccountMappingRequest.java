package com.spe.context.masterdata.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 新增科目映射。 */
public record CreateAccountMappingRequest(
    @NotNull(message = "公司不能为空") @Positive(message = "公司不合法") Long companyId,
    @NotBlank(message = "事件类型不能为空") @Size(max = 64, message = "事件类型长度不得超过 64") String eventType,
    @Size(max = 64, message = "维度长度不得超过 64") String dimension,
    @NotNull(message = "借方科目不能为空") @Positive(message = "借方科目不合法") Long debitAccountId,
    @NotNull(message = "贷方科目不能为空") @Positive(message = "贷方科目不合法") Long creditAccountId,
    @Positive(message = "税科目不合法") Long taxAccountId,
    @NotNull(message = "生效日期不能为空") LocalDate validFrom) {}
