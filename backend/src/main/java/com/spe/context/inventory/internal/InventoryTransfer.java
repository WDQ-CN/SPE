package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 库存调拨（V1016；docs/erp/54 §3.3）。 */
@Data
@TableName("inventory_transfer")
public class InventoryTransfer {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String transferNo;
  private Long companyId;
  private Long fromPlantId;
  private Long fromWarehouseId;
  private Long fromLocationId;
  private Long toPlantId;
  private Long toWarehouseId;
  private Long toLocationId;
  private InventoryTransferStatus status;
  private String reason;
  private Long requestedBy;
  private Long approvedBy;
  private LocalDateTime approvedAt;
  private LocalDateTime shippedAt;
  private LocalDateTime receivedAt;
  private Integer version;
  private LocalDateTime createdAt;
}
