package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 采购订单行（V1008）。 */
@Data
@TableName("purchase_order_line")
public class PurchaseOrderLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long poId;
  private Integer lineNo;
  private Long itemId;
  private BigDecimal qty;
  private String uom;
  private BigDecimal price;
  private BigDecimal amount;
  private BigDecimal receivedQty;
  private BigDecimal invoicedQty;
  private BigDecimal returnedQty;
}
