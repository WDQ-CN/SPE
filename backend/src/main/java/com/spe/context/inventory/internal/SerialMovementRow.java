package com.spe.context.inventory.internal;

import java.time.LocalDateTime;

/** 序列号移动关联行（trace 查询）。 */
public record SerialMovementRow(
    long movementId,
    String movementNo,
    String movementType,
    String direction,
    String sourceType,
    long sourceId,
    LocalDateTime occurredAt) {}
