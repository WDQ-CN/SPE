package com.spe.context.sales.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 创建销售退货。 */
public record CreateSalesReturnRequest(
    @NotNull(message = "发货单不能为空") Long shipmentId,
    @Size(max = 255, message = "退货原因长度不得超过 255") String reason,
    @NotEmpty(message = "至少需要一行退货明细") @Valid List<Line> lines) {

  public record Line(
      @NotNull(message = "发货行不能为空") Long shipmentLineId,
      @NotNull(message = "退货数量不能为空")
          @DecimalMin(value = "0.000001", message = "退货数量必须大于 0")
          BigDecimal qty,
      @Size(max = 255, message = "行原因长度不得超过 255") String reason) {}
}
