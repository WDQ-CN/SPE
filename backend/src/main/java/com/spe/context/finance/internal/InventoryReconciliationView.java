package com.spe.context.finance.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 库存子账 vs GL 对账（docs/erp/54 §4.8）。 */
public record InventoryReconciliationView(
    long companyId,
    LocalDate asOf,
    BigDecimal subledgerValue,
    BigDecimal glValue,
    BigDecimal difference,
    boolean balanced,
    List<InventoryReconciliationRowView> rows) {}
