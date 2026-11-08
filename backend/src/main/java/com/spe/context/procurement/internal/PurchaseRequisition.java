package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.procurement.api.RequisitionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 请购单（V1008；docs/erp/32、48）。 */
@Data
@TableName("purchase_requisition")
public class PurchaseRequisition {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String docNo;
  private Long companyId;
  private Long requesterId;
  private LocalDate needDate;
  private String currency;
  private BigDecimal amount;
  private RequisitionStatus status;
  private Integer version;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
