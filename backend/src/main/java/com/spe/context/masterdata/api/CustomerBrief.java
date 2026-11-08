package com.spe.context.masterdata.api;

import java.math.BigDecimal;

/** 客户跨上下文只读投影（销售/财务用）。 */
public record CustomerBrief(
    long id,
    String code,
    String name,
    String currency,
    String paymentTerms,
    BigDecimal creditLimit,
    BigDecimal creditUsed,
    MasterDataStatus status) {

  public boolean active() {
    return status == MasterDataStatus.ACTIVE;
  }

  public BigDecimal availableCredit() {
    BigDecimal limit = creditLimit == null ? BigDecimal.ZERO : creditLimit;
    BigDecimal used = creditUsed == null ? BigDecimal.ZERO : creditUsed;
    return limit.subtract(used);
  }
}
