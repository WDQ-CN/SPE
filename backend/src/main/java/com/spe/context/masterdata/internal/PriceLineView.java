package com.spe.context.masterdata.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 价格表行投影。 */
public record PriceLineView(
    long id,
    long priceListId,
    long itemId,
    String uom,
    BigDecimal minQty,
    BigDecimal price,
    boolean taxIncluded,
    LocalDate validFrom,
    LocalDate validTo,
    int version) {

  static PriceLineView of(PriceLine line) {
    return new PriceLineView(
        line.getId(),
        line.getPriceListId(),
        line.getItemId(),
        line.getUom(),
        line.getMinQty(),
        line.getPrice(),
        Boolean.TRUE.equals(line.getTaxIncluded()),
        line.getValidFrom(),
        line.getValidTo(),
        line.getVersion());
  }
}
