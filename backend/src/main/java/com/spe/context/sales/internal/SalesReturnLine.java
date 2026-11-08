package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 销售退货行。 */
@Data
@TableName("sales_return_line")
public class SalesReturnLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long returnId;
  private Integer lineNo;
  private Long shipmentLineId;
  private Long orderLineId;
  private Long itemId;
  private BigDecimal qty;
  private BigDecimal unitPrice;
  private BigDecimal discountRate;
  private BigDecimal taxRate;
  private BigDecimal netAmount;
  private BigDecimal taxAmount;
  private String reason;
}
