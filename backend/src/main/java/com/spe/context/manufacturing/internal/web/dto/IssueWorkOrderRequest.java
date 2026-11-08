package com.spe.context.manufacturing.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 工单领料。 */
public record IssueWorkOrderRequest(
    @NotNull(message = "仓库不能为空") Long warehouseId,
    @NotEmpty(message = "至少需要一行领料") @Valid List<Line> lines) {

  public record Line(
      @NotNull(message = "工单组件不能为空") Long componentId,
      @NotNull(message = "领料数量不能为空")
          @DecimalMin(value = "0.000001", message = "领料数量必须大于 0")
          BigDecimal qty,
      @Size(max = 64, message = "批次号长度不得超过 64") String lotNo,
      Long locationId) {}
}
