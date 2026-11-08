package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 客户收款（V1023）。 */
@Data
@TableName("customer_receipt")
public class CustomerReceipt {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String receiptNo;
  private Long companyId;
  private Long customerId;
  private String currency;
  private BigDecimal amount;
  private String method;
  private ReceiptStatus status;
  private LocalDateTime receivedAt;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
}
