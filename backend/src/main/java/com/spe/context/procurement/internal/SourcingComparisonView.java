package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.util.List;

/** 比价矩阵：行=RFQ 行，列=供应商报价；best=true 为该行最低含税总成本。 */
public record SourcingComparisonView(
    long rfqId,
    boolean sealedVisible,
    List<Line> lines) {

  public record Line(
      long rfqLineId,
      long itemId,
      BigDecimal qty,
      Long bestSupplierId,
      List<Cell> cells) {}

  public record Cell(
      long quoteId,
      long quoteLineId,
      long supplierId,
      String supplierCode,
      String supplierName,
      BigDecimal unitPrice,
      BigDecimal taxRate,
      BigDecimal taxIncludedPrice,
      Integer leadTimeDays,
      BigDecimal moq,
      BigDecimal qtyAvailable,
      BigDecimal totalCost,
      boolean best) {}
}
