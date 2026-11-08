package com.spe.context.reporting.internal;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 报表运行投影。 */
public record ReportRunView(
    long id,
    long definitionId,
    String code,
    String name,
    long companyId,
    LocalDate asOf,
    String status,
    int rowCount,
    Long durationMs,
    LocalDateTime createdAt) {}
