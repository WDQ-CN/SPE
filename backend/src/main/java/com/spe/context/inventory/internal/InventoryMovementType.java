package com.spe.context.inventory.internal;

/** 库存移动类型（E1 只用收货与冲销，其余为后续阶段预留值）。 */
public enum InventoryMovementType {
  RECEIPT,
  REVERSAL,
  ISSUE,
  ADJUST,
  RETURN,
  TRANSFER_OUT,
  TRANSFER_IN
}
