package com.spe.context.evaluation.internal.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 改派评分单（01 J2 / 13 §E13）：只允许「待指派 / 评分中」；改派对象须在职且角色合规。 */
public record AssignSheetRequest(
    @Positive(message = "指派对象不合法") long assigneeId,
    @NotNull(message = "缺少乐观锁版本") Integer version) {}
