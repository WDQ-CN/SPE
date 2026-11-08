package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.procurement.api.ApprovalDocType;
import com.spe.context.procurement.api.ApprovalRequiredRole;
import java.math.BigDecimal;
import lombok.Data;

/** 审批矩阵规则（V1008；docs/erp/32、50）。 */
@Data
@TableName("p2p_approval_rule")
public class P2pApprovalRule {

  @TableId(type = IdType.AUTO)
  private Long id;

  /** null = 全局规则；公司级规则优先。 */
  private Long companyId;

  private ApprovalDocType docType;
  private BigDecimal minAmount;
  private BigDecimal maxAmount;
  private ApprovalRequiredRole requiredRole;
  private Integer priority;
  private MasterDataStatus status;
}
