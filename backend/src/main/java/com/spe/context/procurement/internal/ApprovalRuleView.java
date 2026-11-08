package com.spe.context.procurement.internal;

import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.procurement.api.ApprovalDocType;
import com.spe.context.procurement.api.ApprovalRequiredRole;
import java.math.BigDecimal;

/** 审批矩阵的只读投影（前端配置页/W2 验收）。 */
public record ApprovalRuleView(
    long id,
    Long companyId,
    ApprovalDocType docType,
    BigDecimal minAmount,
    BigDecimal maxAmount,
    ApprovalRequiredRole requiredRole,
    int priority,
    MasterDataStatus status) {

  static ApprovalRuleView of(P2pApprovalRule rule) {
    return new ApprovalRuleView(
        rule.getId(),
        rule.getCompanyId(),
        rule.getDocType(),
        rule.getMinAmount(),
        rule.getMaxAmount(),
        rule.getRequiredRole(),
        rule.getPriority(),
        rule.getStatus());
  }
}
