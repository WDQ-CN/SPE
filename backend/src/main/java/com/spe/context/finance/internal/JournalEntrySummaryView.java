package com.spe.context.finance.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 会计凭证列表投影（不含分录行）。 */
public record JournalEntrySummaryView(
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
    LocalDateTime postedAt) {}
