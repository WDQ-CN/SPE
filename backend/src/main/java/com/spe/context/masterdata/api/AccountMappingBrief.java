package com.spe.context.masterdata.api;

import java.time.LocalDate;

/** 业务事件 → 科目映射的跨上下文只读投影（财务过账用）。 */
public record AccountMappingBrief(
    long id,
    long companyId,
    String eventType,
    String dimension,
    long debitAccountId,
    long creditAccountId,
    Long taxAccountId,
    LocalDate validFrom) {}
