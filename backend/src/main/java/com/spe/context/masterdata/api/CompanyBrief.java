package com.spe.context.masterdata.api;

/** 公司的跨上下文只读投影（05 §8.3）。 */
public record CompanyBrief(
    long id, String code, String name, String baseCurrency, MasterDataStatus status) {

  public boolean active() {
    return status == MasterDataStatus.ACTIVE;
  }
}
