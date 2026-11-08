package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.procurement.api.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 采购订单（V1008；docs/erp/32、48）。 */
@Data
@TableName("purchase_order")
public class PurchaseOrder {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String docNo;
  private Long companyId;
  private Long supplierId;
  private Long plantId;
  private Long sourcePrId;
  private String currency;
  private String paymentTerms;
  private BigDecimal amount;
  private PurchaseOrderStatus status;
  private Long createdBy;
  private Long approvedBy;
  private LocalDateTime approvedAt;
  private Integer version;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
