package com.spe.context.template.internal.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 改维度（02 §4 `PUT /api/dimensions/{id}`）。
 *
 * <p>`version` 必填且是**乐观锁版本**（守卫 UPDATE，06 §2.4）：版本不符 → 3001。
 * ⚠️ 若目标是**已发布**版本，服务端会在同一事务里复制出新草稿，此时响应里的
 * `effectiveVersion` 与请求里的 `version` **不同**——前端必须改用手里的新值（12 §E5/R-5）。
 */
public record UpdateDimensionRequest(
    @NotBlank(message = "维度名称不能为空")
        @Size(max = 100, message = "维度名称长度不得超过 100")
        String name,
    @NotNull(message = "权重不能为空")
        @DecimalMin(value = "0", message = "权重不能小于 0")
        @DecimalMax(value = "100", message = "权重不能大于 100")
        @Digits(integer = 3, fraction = 2, message = "权重最多保留两位小数")
        BigDecimal weight,
    @Min(value = 0, message = "排序号不能为负") Integer sortNo,
    @Size(max = 50, message = "建议评估部门长度不得超过 50") String suggestedDept,
    Boolean isKey,
    @NotNull(message = "版本号不能为空") @Min(value = 0, message = "版本号不能为负") Integer version) {}
