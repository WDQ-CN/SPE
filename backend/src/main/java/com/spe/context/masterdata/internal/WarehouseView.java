package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.WarehouseType;

/** 仓库列表/详情投影。 */
public record WarehouseView(
    long id,
    long plantId,
    String code,
    String name,
    WarehouseType warehouseType,
    MasterDataStatus status,
    int version) {

  static WarehouseView of(Warehouse warehouse) {
    return new WarehouseView(
        warehouse.getId(),
        warehouse.getPlantId(),
        warehouse.getCode(),
        warehouse.getName(),
        warehouse.getWarehouseType(),
        warehouse.getStatus(),
        warehouse.getVersion());
  }
}
