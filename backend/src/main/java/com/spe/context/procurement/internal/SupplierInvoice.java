package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.procurement.api.SupplierInvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 供应商发票（V1009；docs/erp/32、34、48）。 */
@Data
@TableName("supplier_invoice")
public class SupplierInvoice {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String docNo;
  private String supplierInvoiceNo;
  private Long companyId;
  private Long supplierId;
  private Long poId;
  private LocalDate invoiceDate;
  private LocalDate dueDate;
  private String currency;
  private BigDecimal netAmount;
  private BigDecimal taxAmount;
  private BigDecimal totalAmount;
  private BigDecimal paidAmount;
  private SupplierInvoiceStatus status;
  private Long createdBy;
  private Long approvedBy;
  private LocalDateTime approvedAt;
  private Integer version;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
