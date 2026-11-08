package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 调拨行。 */
@Data
@TableName("inventory_transfer_line")
public class InventoryTransferLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long transferId;
  private Integer lineNo;
  private Long itemId;
  private Long lotId;
  private BigDecimal qty;
  private String uom;
  private BigDecimal unitCost;
  private BigDecimal amount;
}
