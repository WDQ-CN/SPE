package com.spe.context.sales.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 创建销售发票。 */
public record CreateSalesInvoiceRequest(
    @NotNull(message = "销售订单不能为空") Long orderId,
    @NotNull(message = "到期日不能为空") LocalDate dueDate,
    @Size(max = 255, message = "备注长度不得超过 255") String remark,
    @NotEmpty(message = "至少需要一行开票明细") @Valid List<Line> lines) {

  public record Line(
      @NotNull(message = "订单行不能为空") Long orderLineId,
      @NotNull(message = "开票数量不能为空")
          @DecimalMin(value = "0.000001", message = "开票数量必须大于 0")
          BigDecimal qty) {}
}
