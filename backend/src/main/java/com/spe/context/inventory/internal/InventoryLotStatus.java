package com.spe.context.inventory.internal;

/** 批次状态（E1 只做状态标记与收货准入，质检流程后续）。 */
public enum InventoryLotStatus {
  ACTIVE,
  QUARANTINE,
  EXPIRED,
  CLOSED
}
