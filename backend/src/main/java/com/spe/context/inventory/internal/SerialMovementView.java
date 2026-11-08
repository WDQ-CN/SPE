package com.spe.context.inventory.internal;

import java.time.LocalDateTime;

/** 序列号移动轨迹行。 */
public record SerialMovementView(
    long movementId,
    String movementNo,
    String movementType,
    String direction,
    String sourceType,
    long sourceId,
    LocalDateTime occurredAt) {}
