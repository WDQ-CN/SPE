package com.spe.context.openapi.internal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 开放 API 上报入参（M6）：任务 + 供应商编码 + 指标编码 + 原始值。
 *
 * <p>原始值的业务边界（定性 1~5、比率/目标等）由 ScoreStrategies 在服务层统一判并返回 2025；
 * DTO 只挡缺失与明显超长/超精度。
 */
public record OpenApiScoreRequest(
    @NotNull(message = "任务 id 不能为空") @Min(value = 1, message = "任务 id 不合法")
        Long taskId,
    @NotBlank(message = "供应商编码不能为空")
        @Size(max = 50, message = "供应商编码不得超过 50")
        String supplierCode,
    @NotBlank(message = "指标编码不能为空")
        @Size(max = 50, message = "指标编码不得超过 50")
        String indicatorCode,
    @NotNull(message = "原始值不能为空")
        @Digits(integer = 12, fraction = 4, message = "原始值格式不合法")
        BigDecimal rawValue) {}
