package com.spe.context.analytics.internal.web;

import com.spe.common.web.Result;
import com.spe.context.analytics.internal.AnalyticsService;
import com.spe.context.analytics.internal.AnalyticsViews;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分析看板（01 B1/B2/B4/B5；M5 W4，02 §4）。
 *
 * <p>权限 ADMIN/MANAGER/VIEWER；只读 `EFFECTIVE`。`year/quarter` 必填（前端从 `/periods` 取最新），
 * 对比的 `supplierIds` 限 1~10 个。
 */
@RestController
@RequestMapping("/api/analytics")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','VIEWER')")
@Validated
@RequiredArgsConstructor
public class AnalyticsController {

  private final AnalyticsService analyticsService;

  @GetMapping("/periods")
  public Result<List<AnalyticsViews.Period>> periods() {
    return Result.ok(analyticsService.periods());
  }

  @GetMapping("/suppliers")
  public Result<List<AnalyticsViews.SupplierOption>> suppliers(
      @RequestParam(required = false)
          @Size(max = 100, message = "关键字长度不得超过 100")
          String keyword) {
    return Result.ok(analyticsService.suppliers(keyword));
  }

  @GetMapping("/trend")
  public Result<AnalyticsViews.Trend> trend(@RequestParam long supplierId) {
    return Result.ok(analyticsService.trend(supplierId));
  }

  @GetMapping("/ranking")
  public Result<AnalyticsViews.Ranking> ranking(
      @RequestParam @Min(value = 2000, message = "年份不合法") @Max(value = 2999, message = "年份不合法")
          int year,
      @RequestParam @Min(value = 1, message = "季度必须在 1~4 之间") @Max(value = 4, message = "季度必须在 1~4 之间")
          int quarter,
      @RequestParam(required = false) @Size(max = 50, message = "分类长度不得超过 50") String category,
      @RequestParam(required = false) @Size(max = 10, message = "等级取值不合法") String grade) {
    return Result.ok(analyticsService.ranking(year, quarter, category, grade));
  }

  @GetMapping("/grade-distribution")
  public Result<List<AnalyticsViews.GradeCount>> gradeDistribution(
      @RequestParam @Min(value = 2000, message = "年份不合法") @Max(value = 2999, message = "年份不合法")
          int year,
      @RequestParam @Min(value = 1, message = "季度必须在 1~4 之间") @Max(value = 4, message = "季度必须在 1~4 之间")
          int quarter) {
    return Result.ok(analyticsService.gradeDistribution(year, quarter));
  }

  @GetMapping("/compare")
  public Result<AnalyticsViews.Compare> compare(
      @RequestParam @Min(value = 2000, message = "年份不合法") @Max(value = 2999, message = "年份不合法")
          int year,
      @RequestParam @Min(value = 1, message = "季度必须在 1~4 之间") @Max(value = 4, message = "季度必须在 1~4 之间")
          int quarter,
      @RequestParam
          @Size(min = 1, max = 10, message = "对比供应商须为 1~10 个")
          List<Long> supplierIds) {
    return Result.ok(analyticsService.compare(year, quarter, supplierIds));
  }
}
