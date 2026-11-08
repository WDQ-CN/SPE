package com.spe.context.reporting.internal.web.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** 运行报表。 */
public record RunReportRequest(
    @NotNull(message = "公司不能为空") Long companyId, LocalDate asOf) {}
