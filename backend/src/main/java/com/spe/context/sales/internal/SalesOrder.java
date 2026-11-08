package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 销售订单（V1021；docs/erp/35）。 */
@Data
@TableName("sales_order")
public class SalesOrder {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String orderNo;
  private Long companyId;
  private Long customerId;
  private Long sourceQuoteId;
  private String currency;
  private String paymentTerms;
  private SalesOrderStatus status;
  private BigDecimal netAmount;
  private BigDecimal taxAmount;
  private BigDecimal totalAmount;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long confirmedBy;
  private LocalDateTime confirmedAt;
  private LocalDateTime canceledAt;
}
