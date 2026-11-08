package com.spe.context.finance.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 试算平衡报告。 */
public record TrialBalanceView(
    long companyId,
    LocalDate from,
    LocalDate to,
    List<TrialBalanceRowView> rows,
    BigDecimal totalDebit,
    BigDecimal totalCredit,
    boolean balanced) {}
