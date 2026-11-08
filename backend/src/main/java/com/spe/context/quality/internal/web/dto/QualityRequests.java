package com.spe.context.quality.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 质量模块请求体集合。 */
public final class QualityRequests {

  private QualityRequests() {}

  public record CreateInspectionPlan(
      @NotNull(message = "公司不能为空") Long companyId,
      @NotNull(message = "物料不能为空") Long itemId,
      Long supplierId,
      @Size(max = 16, message = "检验类型过长") String inspectionType,
      @Size(max = 16, message = "版本过长") String version,
      @Size(max = 64, message = "抽样方案过长") String samplingPlan,
      @DecimalMin(value = "0.0", message = "AQL 不能为负") BigDecimal aql,
      @NotEmpty(message = "检验计划至少需要一个特性") @Valid List<Characteristic> characteristics) {

    public record Characteristic(
        @NotBlank(message = "特性名不能为空") @Size(max = 100) String name,
        @Size(max = 255) String spec,
        Boolean critical,
        @Size(max = 64) String method) {}
  }

  public record CreateInspectionOrder(
      @NotNull(message = "公司不能为空") Long companyId,
      @NotNull(message = "检验计划不能为空") Long planId,
      @Size(max = 32, message = "来源类型过长") String sourceType,
      Long sourceId,
      Long sourceLineId,
      @NotNull(message = "物料不能为空") Long itemId,
      Long supplierId,
      @Size(max = 64, message = "批次号过长") String lotNo,
      @NotNull(message = "检验数量不能为空")
          @DecimalMin(value = "0.000001", message = "检验数量必须大于 0")
          BigDecimal qty) {}

  public record RecordResults(
      @NotEmpty(message = "至少需要一条检验结果") @Valid List<ResultLine> lines) {

    public record ResultLine(
        Long characteristicId,
        @NotBlank(message = "特性名不能为空") @Size(max = 100) String name,
        @Size(max = 100) String measuredValue,
        @NotBlank(message = "判定不能为空") @Size(max = 16) String result,
        @Size(max = 255) String remark) {}
  }

  public record CreateNcr(
      @NotNull(message = "公司不能为空") Long companyId,
      @NotNull(message = "物料不能为空") Long itemId,
      Long supplierId,
      @Size(max = 64) String lotNo,
      @DecimalMin(value = "0.0", message = "数量不能为负") BigDecimal qty,
      @NotBlank(message = "缺陷描述不能为空") @Size(max = 255) String defect) {}

  public record DisposeNcr(
      @NotBlank(message = "处置方式不能为空") @Size(max = 24) String disposition,
      @NotBlank(message = "处置原因不能为空") @Size(max = 255) String reason) {}

  public record CreateCapa(
      @NotNull(message = "NCR 不能为空") Long ncrId,
      @Size(max = 500) String rootCause,
      @NotBlank(message = "措施计划不能为空") @Size(max = 1000) String actionPlan) {}

  public record CreateCertificate(
      @NotNull(message = "公司不能为空") Long companyId,
      @NotNull(message = "供应商不能为空") Long supplierId,
      Long itemId,
      @NotBlank(message = "证书类型不能为空") @Size(max = 32) String certType,
      @NotBlank(message = "证书编号不能为空") @Size(max = 64) String certNo,
      @NotNull(message = "生效日不能为空") LocalDate validFrom,
      @NotNull(message = "失效日不能为空") LocalDate validTo) {}
}
