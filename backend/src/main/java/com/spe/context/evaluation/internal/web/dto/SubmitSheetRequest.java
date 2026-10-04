package com.spe.context.evaluation.internal.web.dto;

import jakarta.validation.constraints.NotNull;

/** 提交评分单（01 E4 / 13 §E9）：带乐观锁版本；缺值 → 2024（message 点名编码）。 */
public record SubmitSheetRequest(@NotNull(message = "缺少乐观锁版本") Integer version) {}
