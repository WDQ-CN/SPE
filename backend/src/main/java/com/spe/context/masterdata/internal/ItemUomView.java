package com.spe.context.masterdata.internal;

import java.math.BigDecimal;

/** 物料计量单位换算投影。 */
public record ItemUomView(
    long id,
    long itemId,
    String fromUom,
    String toUom,
    BigDecimal numerator,
    BigDecimal denominator,
    int version) {

  static ItemUomView of(ItemUomConversion conversion) {
    return new ItemUomView(
        conversion.getId(),
        conversion.getItemId(),
        conversion.getFromUom(),
        conversion.getToUom(),
        conversion.getNumerator(),
        conversion.getDenominator(),
        conversion.getVersion());
  }
}
