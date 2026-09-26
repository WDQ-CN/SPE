package com.spe.context.template.internal.web.dto;

import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 改指标（02 §4 `PUT /api/indicators/{id}`）。
 *
 * <p>`standard` 是**算法载体**：DIRECT 指标的换算表写在这里（12 §E15），改它等于改计分口径，
 * 所以服务层会把它记进审计 detail。
 */
public record UpdateIndicatorRequest(
    @NotBlank(message = "指标名称不能为空")
        @Size(max = 100, message = "指标名称长度不得超过 100")
        String name,
    @NotBlank(message = "指标编码不能为空")
        @Size(max = 50, message = "指标编码长度不得超过 50")
        String code,
    @NotNull(message = "权重不能为空")
        @DecimalMin(value = "0", message = "权重不能小于 0")
        @DecimalMax(value = "100", message = "权重不能大于 100")
        @Digits(integer = 3, fraction = 2, message = "权重最多保留两位小数")
        BigDecimal weight,
    @NotNull(message = "指标类型不能为空") IndicatorType type,
    ScoreMode scoreMode,
    @Size(max = 20, message = "单位长度不得超过 20") String unit,
    @Digits(integer = 8, fraction = 4, message = "目标值最多保留四位小数") BigDecimal targetValue,
    @Size(max = 500, message = "评分标准长度不得超过 500") String standard,
    @Min(value = 0, message = "排序号不能为负") Integer sortNo,
    @NotNull(message = "版本号不能为空") @Min(value = 0, message = "版本号不能为负") Integer version) {}
