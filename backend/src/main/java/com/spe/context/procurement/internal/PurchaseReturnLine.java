package com.spe.context.procurement.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 采购退货行（V1012）。 */
@Data
@TableName("purchase_return_line")
public class PurchaseReturnLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long returnId;
  private Integer lineNo;
  private Long poLineId;
  private Long itemId;
  private BigDecimal qty;
  private String uom;
  private BigDecimal unitCost;
  private BigDecimal amount;
  private String lotNo;

  @TableField(exist = false)
  private java.util.List<String> serials;

  private Long locationId;
}
