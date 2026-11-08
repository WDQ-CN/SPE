package com.spe.context.manufacturing.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** 创建生产工单。 */
public record CreateWorkOrderRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotNull(message = "工厂不能为空") Long plantId,
    @NotNull(message = "产出物料不能为空") Long itemId,
    @NotNull(message = "BOM 不能为空") Long bomId,
    @NotNull(message = "计划数量不能为空")
        @DecimalMin(value = "0.000001", message = "计划数量必须大于 0")
        BigDecimal qty) {}
