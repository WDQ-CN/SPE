package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.procurement.api.PaymentRequestStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 付款申请（V1009；docs/erp/32、34、48）。 */
@Data
@TableName("payment_request")
public class PaymentRequest {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String docNo;
  private Long companyId;
  private Long supplierId;
  private Long invoiceId;
  private BigDecimal amount;
  private String currency;
  private PaymentRequestStatus status;
  private Long requestedBy;
  private Long approvedBy;
  private LocalDateTime approvedAt;
  private LocalDateTime paidAt;
  private Integer version;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
