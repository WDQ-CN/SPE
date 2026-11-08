package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 库存移动台账（V1011；不可变，来源唯一键幂等）。 */
@Data
@TableName("inventory_movement")
public class InventoryMovement {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String movementNo;
  private Long companyId;
  private Long plantId;
  private Long warehouseId;
  private Long itemId;
  private Long lotId;
  private Long locationId;
  private InventoryMovementType movementType;
  private MovementDirection direction;
  private BigDecimal qty;
  private String uom;
  private BigDecimal unitCost;
  private BigDecimal amount;
  private String currency;
  private String sourceType;
  private Long sourceId;
  private Long sourceLineId;
  private LocalDateTime occurredAt;
  private Long createdBy;
  private LocalDateTime createdAt;
}
