package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;

/** 新增请购单。 */
public record CreateRequisitionRequest(
    @Positive(message = "公司 id 必须为正") long companyId,
    @NotNull(message = "需求日期不能为空") LocalDate needDate,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @NotEmpty(message = "至少需要一行请购明细") List<@Valid RequisitionLineRequest> lines) {}
