package com.spe.context.finance.internal;

import java.math.BigDecimal;

/** 试算平衡行：借方发生额、贷方发生额与借-贷余额。 */
public record TrialBalanceRowView(
    long accountId,
    String accountCode,
    String accountName,
    BigDecimal debitTotal,
    BigDecimal creditTotal,
    BigDecimal balance) {}
