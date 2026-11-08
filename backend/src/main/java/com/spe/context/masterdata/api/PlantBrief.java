package com.spe.context.masterdata.api;

/** 工厂的跨上下文只读投影（05 §8.3）。 */
public record PlantBrief(
    long id, long companyId, String code, String name, MasterDataStatus status) {

  public boolean active() {
    return status == MasterDataStatus.ACTIVE;
  }
}
