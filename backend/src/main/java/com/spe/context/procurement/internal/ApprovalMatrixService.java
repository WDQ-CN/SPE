package com.spe.context.procurement.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.procurement.api.ApprovalDocType;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 审批矩阵解析（docs/erp/50 §2）：金额 → 所需角色；ADMIN 满足全部，MANAGER 只满足 MANAGER。 */
@Component
@RequiredArgsConstructor
public class ApprovalMatrixService {

  private final P2pApprovalRuleMapper ruleMapper;

  @Transactional(readOnly = true)
  public P2pApprovalRule resolve(ApprovalDocType docType, long companyId, BigDecimal amount) {
    return ruleMapper
        .findApplicable(docType, companyId, amount)
        .orElseThrow(() -> new BizException(ErrorCode.APPROVAL_RULE_NOT_CONFIGURED));
  }

  /** 审批人角色是否达到单据所需级别；不够则 4005（不是 403 泛码，前端要能提示"需升级审批"）。 */
  @Transactional(readOnly = true)
  public P2pApprovalRule requireSatisfied(
      ApprovalDocType docType, long companyId, BigDecimal amount, boolean admin) {
    P2pApprovalRule rule = resolve(docType, companyId, amount);
    if (!rule.getRequiredRole().satisfiedBy(admin)) {
      throw new BizException(
          ErrorCode.APPROVAL_ROLE_INSUFFICIENT,
          "当前金额需要 " + rule.getRequiredRole() + " 级别审批");
    }
    return rule;
  }

  @Transactional(readOnly = true)
  public List<ApprovalRuleView> list(String docType) {
    ApprovalDocType parsed = P2pSupport.parse(ApprovalDocType.class, docType, "单据类型");
    return ruleMapper.findAll(parsed).stream().map(ApprovalRuleView::of).toList();
  }
}
