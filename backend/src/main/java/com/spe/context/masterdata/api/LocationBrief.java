package com.spe.context.masterdata.api;

/** 仓库库位的跨上下文只读投影（收货/库存校验）。 */
public record LocationBrief(
    long id,
    long warehouseId,
    String code,
    String name,
    MasterDataStatus status) {

  public boolean active() {
    return status == MasterDataStatus.ACTIVE;
  }
}
