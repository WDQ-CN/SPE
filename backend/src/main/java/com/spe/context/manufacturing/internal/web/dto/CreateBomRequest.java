package com.spe.context.manufacturing.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 创建 BOM。 */
public record CreateBomRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotNull(message = "产出物料不能为空") Long itemId,
    @Size(max = 16, message = "版本长度不得超过 16") String version,
    @NotEmpty(message = "BOM 至少需要一行") @Valid List<Line> lines) {

  public record Line(
      @NotNull(message = "组件物料不能为空") Long componentItemId,
      @NotNull(message = "用量不能为空")
          @DecimalMin(value = "0.000001", message = "用量必须大于 0")
          BigDecimal qty,
      @Size(max = 16, message = "单位长度不得超过 16") String uom) {}
}
