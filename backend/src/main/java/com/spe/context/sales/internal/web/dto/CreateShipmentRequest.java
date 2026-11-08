package com.spe.context.sales.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 创建销售发货。 */
public record CreateShipmentRequest(
    @NotNull(message = "销售订单不能为空") Long orderId,
    @NotNull(message = "仓库不能为空") Long warehouseId,
    @NotEmpty(message = "至少需要一行发货明细") @Valid List<Line> lines) {

  public record Line(
      @NotNull(message = "订单行不能为空") Long orderLineId,
      @NotNull(message = "发货数量不能为空")
          @DecimalMin(value = "0.000001", message = "发货数量必须大于 0")
          BigDecimal qty,
      @Size(max = 64, message = "批次号长度不得超过 64") String lotNo,
      Long locationId) {}
}
