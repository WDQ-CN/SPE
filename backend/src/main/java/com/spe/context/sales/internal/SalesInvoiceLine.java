package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 销售发票行。 */
@Data
@TableName("sales_invoice_line")
public class SalesInvoiceLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long invoiceId;
  private Integer lineNo;
  private Long orderLineId;
  private Long shipmentLineId;
  private Long itemId;
  private BigDecimal qty;
  private BigDecimal unitPrice;
  private BigDecimal discountRate;
  private BigDecimal taxRate;
  private BigDecimal netAmount;
  private BigDecimal taxAmount;
}
