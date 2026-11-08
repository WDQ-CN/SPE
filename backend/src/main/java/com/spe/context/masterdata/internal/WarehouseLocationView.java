package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.MasterDataStatus;

/** 库位投影。 */
public record WarehouseLocationView(
    long id,
    long warehouseId,
    String code,
    String name,
    String zone,
    String aisle,
    String rack,
    String levelNo,
    String binNo,
    MasterDataStatus status,
    int version) {

  static WarehouseLocationView of(WarehouseLocation location) {
    return new WarehouseLocationView(
        location.getId(),
        location.getWarehouseId(),
        location.getCode(),
        location.getName(),
        location.getZone(),
        location.getAisle(),
        location.getRack(),
        location.getLevelNo(),
        location.getBinNo(),
        location.getStatus(),
        location.getVersion());
  }
}
