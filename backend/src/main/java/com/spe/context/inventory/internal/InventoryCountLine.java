package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 盘点行。 */
@Data
@TableName("inventory_count_line")
public class InventoryCountLine {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long countId;
  private Integer lineNo;
  private Long itemId;
  private Long lotId;
  private Long locationId;
  private BigDecimal snapshotQty;
  private BigDecimal countedQty;
  private BigDecimal varianceQty;
  private String reason;
}
