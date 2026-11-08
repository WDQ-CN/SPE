package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 库存预留（V1016；docs/erp/54 §3.2）。 */
@Data
@TableName("inventory_reservation")
public class InventoryReservation {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long companyId;
  private Long warehouseId;
  private Long locationId;
  private Long itemId;
  private Long lotId;
  private Long serialId;
  private BigDecimal qty;
  private String sourceType;
  private Long sourceId;
  private Long sourceLineId;
  private InventoryReservationStatus status;
  private LocalDateTime expiresAt;
  private Long createdBy;
  private Integer version;
  private LocalDateTime createdAt;
}
