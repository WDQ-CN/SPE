package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.List;

/** 修改请购单（仅 DRAFT）。 */
public record UpdateRequisitionRequest(
    @NotNull(message = "需求日期不能为空") LocalDate needDate,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @NotEmpty(message = "至少需要一行请购明细") List<@Valid RequisitionLineRequest> lines,
    @PositiveOrZero(message = "版本号不能为负") int version) {}
