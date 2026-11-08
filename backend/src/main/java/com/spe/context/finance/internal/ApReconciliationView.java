package com.spe.context.finance.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** AP 子账与 GL 应付科目对账报告（FIN-004 的最小实现）。 */
public record ApReconciliationView(
    long companyId,
    LocalDate asOf,
    BigDecimal invoicedTotal,
    BigDecimal paidTotal,
    BigDecimal returnedTotal,
    BigDecimal subledgerBalance,
    BigDecimal glBalance,
    BigDecimal difference,
    boolean reconciled) {}
