package com.spe.context.inventory.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 创建库存预留。 */
public record CreateReservationRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotNull(message = "仓库不能为空") Long warehouseId,
    Long locationId,
    @NotNull(message = "物料不能为空") Long itemId,
    Long lotId,
    Long serialId,
    @NotNull(message = "数量不能为空")
        @DecimalMin(value = "0.000001", message = "数量必须大于 0")
        BigDecimal qty,
    @NotBlank(message = "来源类型不能为空") @Size(max = 32, message = "来源类型过长") String sourceType,
    @NotNull(message = "来源单据不能为空") Long sourceId,
    Long sourceLineId,
    LocalDateTime expiresAt) {}
