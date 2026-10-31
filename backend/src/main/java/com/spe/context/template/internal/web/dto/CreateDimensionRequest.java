package com.spe.context.template.internal.web.dto;

import com.spe.common.validation.Limits;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 新增维度（02 §4 `POST /api/template-versions/{id}/dimensions`）。
 *
 * <p>`weight` 用 `DECIMAL(5,2)` 对齐：`@Digits(integer=3, fraction=2)` 拦精度，`0~100` 拦范围。
 * 三样缺一不可——只有范围没有精度时，`33.335` 会被库静默四舍五入成 `33.34`（或严格模式报错 → 5001），
 * 而那是"用户以为存了什么"与"库里是什么"不一致（12 §E3）。
 *
 * <p>`sortNo` / `isKey` 允许缺省：它们不影响任何不变量，服务层补默认值即可，不必逼调用方每次都填。
 */
public record CreateDimensionRequest(
    @NotBlank(message = "维度名称不能为空")
        @Size(max = Limits.NAME_100, message = "维度名称长度不得超过 100")
        String name,
    @NotNull(message = "权重不能为空")
        @DecimalMin(value = "0", message = "权重不能小于 0")
        @DecimalMax(value = "100", message = "权重不能大于 100")
        @Digits(integer = 3, fraction = 2, message = "权重最多保留两位小数")
        BigDecimal weight,
    @Min(value = 0, message = "排序号不能为负") Integer sortNo,
    @Size(max = Limits.TEXT_50, message = "建议评估部门长度不得超过 50") String suggestedDept,
    Boolean isKey) {}
