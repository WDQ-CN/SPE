package com.spe.context.finance.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 会计凭证详情投影。 */
public record JournalEntryView(
    long id,
    String docNo,
    long companyId,
    String companyName,
    LocalDate entryDate,
    String sourceType,
    long sourceId,
    String currency,
    String description,
    BigDecimal totalAmount,
    String status,
    long createdBy,
    String createdByName,
    LocalDateTime postedAt,
    LocalDateTime createdAt,
    List<JournalLineView> lines) {}
