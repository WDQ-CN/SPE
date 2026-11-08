package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 销售发票（V1023）。 */
@Data
@TableName("sales_invoice")
public class SalesInvoice {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String invoiceNo;
  private Long companyId;
  private Long customerId;
  private Long orderId;
  private String currency;
  private SalesInvoiceStatus status;
  private BigDecimal netAmount;
  private BigDecimal taxAmount;
  private BigDecimal totalAmount;
  private BigDecimal paidAmount;
  private LocalDateTime issuedAt;
  private LocalDate dueDate;
  private String remark;
  private Integer version;
  private Long createdBy;
  private LocalDateTime createdAt;
}
