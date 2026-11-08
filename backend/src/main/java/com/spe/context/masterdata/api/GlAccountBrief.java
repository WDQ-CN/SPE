package com.spe.context.masterdata.api;

/** 会计科目的跨上下文只读投影（过账校验与试算平衡展示）。 */
public record GlAccountBrief(
    long id,
    long companyId,
    String code,
    String name,
    AccountType accountType,
    NormalBalance normalBalance,
    MasterDataStatus status) {

  public boolean active() {
    return status == MasterDataStatus.ACTIVE;
  }
}
