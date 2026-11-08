package com.spe.context.inventory.internal;

import java.util.List;

/** 序列号追溯报告。 */
public record SerialTraceView(
    InventorySerialView serial, List<SerialMovementView> movements) {}
