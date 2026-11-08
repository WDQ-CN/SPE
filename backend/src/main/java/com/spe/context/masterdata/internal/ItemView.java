package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.ItemType;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.ValuationMethod;

/** 物料列表/详情投影。 */
public record ItemView(
    long id,
    String code,
    String name,
    String spec,
    ItemType itemType,
    String baseUom,
    boolean lotControlled,
    boolean serialControlled,
    ValuationMethod valuationMethod,
    java.math.BigDecimal standardCost,
    MasterDataStatus status,
    int version) {

  static ItemView of(Item item) {
    return new ItemView(
        item.getId(),
        item.getCode(),
        item.getName(),
        item.getSpec(),
        item.getItemType(),
        item.getBaseUom(),
        Boolean.TRUE.equals(item.getLotControlled()),
        Boolean.TRUE.equals(item.getSerialControlled()),
        item.getValuationMethod(),
        item.getStandardCost(),
        item.getStatus(),
        item.getVersion());
  }
}
