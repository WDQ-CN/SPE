package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 采购合同/框架协议（V1018；docs/erp/55 §3.5）。 */
@Data
@TableName("purchase_contract")
public class PurchaseContract {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String contractNo;
  private Long companyId;
  private Long supplierId;
  private String title;
  private String currency;
  private LocalDate validFrom;
  private LocalDate validTo;
  private String paymentTerms;
  private PurchaseContractStatus status;
  private BigDecimal totalLimitAmount;
  private BigDecimal totalLimitQty;
  private Long sourceAwardId;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long approvedBy;
  private LocalDateTime approvedAt;
}
