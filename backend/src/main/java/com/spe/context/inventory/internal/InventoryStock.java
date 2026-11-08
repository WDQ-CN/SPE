package com.spe.context.inventory.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/** 库存余额（V1011；移动平均）。 */
@Data
@TableName("inventory_stock")
public class InventoryStock {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long companyId;
  private Long plantId;
  private Long warehouseId;
  private Long itemId;
  private Long lotId;
  private Long locationId;
  private BigDecimal qtyOnHand;
  private BigDecimal qtyReserved;
  private BigDecimal qtyFrozen;
  private BigDecimal totalValue;
  private BigDecimal avgUnitCost;
  private Integer version;

  public BigDecimal availableQty() {
    BigDecimal reserved = qtyReserved == null ? BigDecimal.ZERO : qtyReserved;
    BigDecimal frozen = qtyFrozen == null ? BigDecimal.ZERO : qtyFrozen;
    return qtyOnHand.subtract(reserved).subtract(frozen);
  }
}
