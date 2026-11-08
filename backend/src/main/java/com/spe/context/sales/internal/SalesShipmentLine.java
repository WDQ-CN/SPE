package com.spe.context.sales.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 销售发货行。 */
@Data
@TableName("sales_shipment_line")
public class SalesShipmentLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long shipmentId;
  private Integer lineNo;
  private Long orderLineId;
  private Long itemId;
  private String lotNo;
  private Long locationId;
  private BigDecimal qty;
  private BigDecimal costUnit;
  private BigDecimal costAmount;
  private BigDecimal returnedQty;
}
