package com.spe.context.masterdata.internal;

import com.spe.context.masterdata.api.MasterDataStatus;
import java.time.LocalDate;

/** 科目映射投影。 */
public record AccountMappingView(
    long id,
    long companyId,
    String eventType,
    String dimension,
    long debitAccountId,
    long creditAccountId,
    Long taxAccountId,
    LocalDate validFrom,
    MasterDataStatus status,
    int version) {

  static AccountMappingView of(AccountMapping mapping) {
    return new AccountMappingView(
        mapping.getId(),
        mapping.getCompanyId(),
        mapping.getEventType(),
        mapping.getDimension(),
        mapping.getDebitAccountId(),
        mapping.getCreditAccountId(),
        mapping.getTaxAccountId(),
        mapping.getValidFrom(),
        mapping.getStatus(),
        mapping.getVersion());
  }
}
