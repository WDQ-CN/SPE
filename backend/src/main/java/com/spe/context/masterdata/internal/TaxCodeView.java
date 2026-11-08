package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.TaxType;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 税码投影。 */
public record TaxCodeView(
    long id,
    String code,
    String country,
    BigDecimal rate,
    TaxType taxType,
    LocalDate effectiveFrom,
    MasterDataStatus status,
    int version) {

  static TaxCodeView of(TaxCode taxCode) {
    return new TaxCodeView(
        taxCode.getId(),
        taxCode.getCode(),
        taxCode.getCountry(),
        taxCode.getRate(),
        taxCode.getTaxType(),
        taxCode.getEffectiveFrom(),
        taxCode.getStatus(),
        taxCode.getVersion());
  }
}
