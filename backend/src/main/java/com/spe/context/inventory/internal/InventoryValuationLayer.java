package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.masterdata.api.ValuationMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 库存估值层（V1016；FIFO/STANDARD）。 */
@Data
@TableName("inventory_valuation_layer")
public class InventoryValuationLayer {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long companyId;
  private Long warehouseId;
  private Long itemId;
  private Long lotId;
  private ValuationMethod method;
  private Long sourceMovementId;
  private LocalDate layerDate;
  private BigDecimal qty;
  private BigDecimal remainingQty;
  private BigDecimal unitCost;
  private BigDecimal amount;
  private BigDecimal remainingAmount;
  private ValuationLayerStatus status;
  private LocalDateTime createdAt;
}
