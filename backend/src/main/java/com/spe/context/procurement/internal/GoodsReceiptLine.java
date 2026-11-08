package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 收货单行（V1008）。 */
@Data
@TableName("goods_receipt_line")
public class GoodsReceiptLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long grId;
  private Integer lineNo;
  private Long poLineId;
  private Long itemId;
  private BigDecimal qty;
  private String uom;
  private String lotNo;
  private Long locationId;
}
