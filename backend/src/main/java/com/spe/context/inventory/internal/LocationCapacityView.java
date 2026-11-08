package com.spe.context.inventory.internal;

import com.spe.context.masterdata.api.MasterDataStatus;
import java.math.BigDecimal;

/** 库位容量投影（currentQty 为校验时的实时占用）。 */
public record LocationCapacityView(
    long id,
    long locationId,
    String locationCode,
    String locationName,
    long itemId,
    String itemCode,
    BigDecimal maxQty,
    String uom,
    BigDecimal currentQty,
    MasterDataStatus status,
    int version) {}
