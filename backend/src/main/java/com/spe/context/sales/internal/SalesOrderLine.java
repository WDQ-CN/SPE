package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 销售订单行。 */
@Data
@TableName("sales_order_line")
public class SalesOrderLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long orderId;
  private Integer lineNo;
  private Long itemId;
  private BigDecimal qty;
  private String uom;
  private BigDecimal unitPrice;
  private BigDecimal discountRate;
  private BigDecimal taxRate;
  private BigDecimal netAmount;
  private BigDecimal taxAmount;
  private BigDecimal shippedQty;
  private BigDecimal invoicedQty;
}
