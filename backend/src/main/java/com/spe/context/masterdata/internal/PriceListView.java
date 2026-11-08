package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.PriceListType;
import java.time.LocalDate;

/** 价格表投影。 */
public record PriceListView(
    long id,
    String code,
    PriceListType listType,
    String currency,
    LocalDate validFrom,
    LocalDate validTo,
    MasterDataStatus status,
    int version) {

  static PriceListView of(PriceList priceList) {
    return new PriceListView(
        priceList.getId(),
        priceList.getCode(),
        priceList.getListType(),
        priceList.getCurrency(),
        priceList.getValidFrom(),
        priceList.getValidTo(),
        priceList.getStatus(),
        priceList.getVersion());
  }
}
