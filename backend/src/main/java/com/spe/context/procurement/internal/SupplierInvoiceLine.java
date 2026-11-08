package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 供应商发票行（V1009）。 */
@Data
@TableName("supplier_invoice_line")
public class SupplierInvoiceLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long invoiceId;
  private Integer lineNo;
  private Long poLineId;
  private Long itemId;
  private BigDecimal qty;
  private BigDecimal unitPrice;
  private BigDecimal amount;
  private BigDecimal taxAmount;
}
