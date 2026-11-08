package com.spe.context.asset.internal.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 资产请求体集合。 */
public final class AssetRequests {

  private AssetRequests() {}

  public record CreateAsset(
      @NotNull(message = "公司不能为空") Long companyId,
      Long plantId,
      @NotBlank(message = "资产类别不能为空") @Size(max = 50) String category,
      @NotBlank(message = "资产名称不能为空") @Size(max = 200) String name,
      @Size(max = 32) String sourceType,
      Long sourceId,
      @NotNull(message = "原值不能为空")
          @DecimalMin(value = "0.0001", message = "原值必须大于 0")
          BigDecimal cost,
      @DecimalMin(value = "0.0", message = "残值不能为负") BigDecimal salvageValue,
      @NotNull(message = "使用寿命不能为空")
          @Min(value = 1, message = "使用寿命必须大于 0")
          Integer usefulLifeMonths,
      @Size(max = 16) String depreciationMethod) {}

  public record Depreciate(
      @NotBlank(message = "折旧期间不能为空")
          @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "期间必须为 YYYY-MM")
          String period) {}

  public record Dispose(
      @NotNull(message = "处置收入不能为空")
          @DecimalMin(value = "0.0", message = "处置收入不能为负")
          BigDecimal proceeds) {}
}
