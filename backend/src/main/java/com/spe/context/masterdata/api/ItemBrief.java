package com.spe.context.masterdata.api;

import java.math.BigDecimal;

/** 物料的跨上下文只读投影（05 §8.3）。 */
public record ItemBrief(
    long id,
    String code,
    String name,
    String baseUom,
    boolean lotControlled,
    boolean serialControlled,
    ItemType itemType,
    ValuationMethod valuationMethod,
    BigDecimal standardCost,
    MasterDataStatus status) {

  public boolean active() {
    return status == MasterDataStatus.ACTIVE;
  }
}
