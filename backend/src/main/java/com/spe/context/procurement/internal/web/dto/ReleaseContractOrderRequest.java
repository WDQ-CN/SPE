package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/** 合同释放采购订单。 */
public record ReleaseContractOrderRequest(
    @NotNull(message = "工厂不能为空") Long plantId,
    @NotEmpty(message = "释放订单至少需要一行") @Valid List<Line> lines) {

  public record Line(
      @NotNull(message = "合同行不能为空") Long contractLineId,
      @NotNull(message = "释放数量不能为空")
          @DecimalMin(value = "0.000001", message = "释放数量必须大于 0")
          BigDecimal qty) {}
}
