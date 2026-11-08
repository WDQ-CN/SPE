package com.spe.context.project.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 项目/工时请求体集合。 */
public final class ProjectRequests {

  private ProjectRequests() {}

  public record CreateProject(
      @NotNull(message = "公司不能为空") Long companyId,
      Long customerId,
      @NotBlank(message = "项目名称不能为空") @Size(max = 200) String name,
      @Size(max = 3) String currency,
      @DecimalMin(value = "0.0", message = "人工费率不能为负") BigDecimal defaultLaborRate,
      LocalDate startDate,
      LocalDate endDate,
      Long managerId) {}

  public record AddWbs(
      Long parentId,
      @NotBlank(message = "WBS 编码不能为空") @Size(max = 32) String code,
      @NotBlank(message = "WBS 名称不能为空") @Size(max = 200) String name,
      @DecimalMin(value = "0.0", message = "预算不能为负") BigDecimal budget) {}

  public record CreateBudget(
      @Size(max = 16) String versionNo,
      @DecimalMin(value = "0.0") BigDecimal laborAmount,
      @DecimalMin(value = "0.0") BigDecimal purchaseAmount,
      @DecimalMin(value = "0.0") BigDecimal expenseAmount,
      @DecimalMin(value = "0.0") BigDecimal otherAmount) {}

  public record CreateTimesheet(
      @NotNull(message = "公司不能为空") Long companyId,
      @NotEmpty(message = "至少需要一行工时") @Valid List<Line> lines) {

    public record Line(
        @NotNull(message = "项目不能为空") Long projectId,
        Long wbsId,
        @NotNull(message = "工作日期不能为空") LocalDate workDate,
        @NotNull(message = "工时不能为空")
            @DecimalMin(value = "0.01", message = "工时必须大于 0")
            BigDecimal hours) {}
  }
}
