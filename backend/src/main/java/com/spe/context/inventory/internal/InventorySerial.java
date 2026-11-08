package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 库存序列号（V1015）。 */
@Data
@TableName("inventory_serial")
public class InventorySerial {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String serialNo;
  private Long itemId;
  private Long lotId;
  private Long supplierId;
  private String sourceType;
  private Long sourceId;
  private Long sourceLineId;
  private Long warehouseId;
  private Long locationId;
  private InventorySerialStatus status;
  private Integer version;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
