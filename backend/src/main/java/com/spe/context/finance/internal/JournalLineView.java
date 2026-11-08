package com.spe.context.finance.internal;

import java.math.BigDecimal;

/** 分录行投影。 */
public record JournalLineView(
    long id,
    int lineNo,
    long accountId,
    String accountCode,
    String accountName,
    BigDecimal debit,
    BigDecimal credit,
    String description) {}
