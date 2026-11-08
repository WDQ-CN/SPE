package com.spe.context.procurement.api;

import java.math.BigDecimal;

/** 供应商应付子账汇总（W5 的 AP 对账输入）。 */
public record ApSubledgerSummary(
    BigDecimal invoicedTotal,
    BigDecimal paidTotal,
    BigDecimal returnedTotal,
    BigDecimal balance) {}
