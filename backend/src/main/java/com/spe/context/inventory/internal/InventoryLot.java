package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 库存批次（V1013；由收货自动建档）。 */
@Data
@TableName("inventory_lot")
public class InventoryLot {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String lotNo;
  private Long itemId;
  private Long supplierId;
  private String sourceType;
  private Long sourceId;
  private LocalDate productionDate;
  private LocalDate expiryDate;
  private InventoryLotStatus status;
  private Integer version;
  private LocalDateTime createdAt;
}
