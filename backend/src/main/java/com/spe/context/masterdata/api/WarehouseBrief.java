package com.spe.context.masterdata.api;

/** 仓库的跨上下文只读投影（05 §8.3）。 */
public record WarehouseBrief(
    long id, long plantId, String code, String name, MasterDataStatus status) {

  public boolean active() {
    return status == MasterDataStatus.ACTIVE;
  }
}
