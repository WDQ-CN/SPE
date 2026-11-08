package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.AccountType;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.masterdata.api.NormalBalance;

/** 会计科目投影。 */
public record GlAccountView(
    long id,
    long companyId,
    String code,
    String name,
    AccountType accountType,
    NormalBalance normalBalance,
    boolean requiresCostCenter,
    MasterDataStatus status,
    int version) {

  static GlAccountView of(GlAccount account) {
    return new GlAccountView(
        account.getId(),
        account.getCompanyId(),
        account.getCode(),
        account.getName(),
        account.getAccountType(),
        account.getNormalBalance(),
        Boolean.TRUE.equals(account.getRequiresCostCenter()),
        account.getStatus(),
        account.getVersion());
  }
}
