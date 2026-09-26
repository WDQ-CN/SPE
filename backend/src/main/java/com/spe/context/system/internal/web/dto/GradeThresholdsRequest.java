package com.spe.context.system.internal.web.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 等级阈值入参（01 §6.3 / 10 §E14）。
 *
 * <p>「0 ≤ D ≤ C ≤ B ≤ A ≤ 100」（含允许相等）是**跨字段**约束，注解表达不了，由 Service 用
 * {@code GradeThresholds.satisfiesOrdering()} 校验后转 `THRESHOLD_INVALID(2005)`。
 * 这里只挡住「字段缺失」与「明显越界」两类，避免为同一个规则写两套口径。
 */
public record GradeThresholdsRequest(
    @NotNull(message = "A 档阈值不能为空") BigDecimal a,
    @NotNull(message = "B 档阈值不能为空") BigDecimal b,
    @NotNull(message = "C 档阈值不能为空") BigDecimal c,
    @NotNull(message = "D 档阈值不能为空") BigDecimal d) {}
