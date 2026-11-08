package com.spe.context.finance.internal.web;

import com.spe.common.web.Result;
import com.spe.context.finance.internal.ApReconciliationView;
import com.spe.context.finance.internal.FinanceReportService;
import com.spe.context.finance.internal.InventoryReconciliationView;
import com.spe.context.finance.internal.TrialBalanceView;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 最小财务报表（W5）：试算平衡与 AP 子账对账（docs/erp/34 §9、52）。 */
@RestController
@RequestMapping("/api/finance/reports")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class FinanceReportController {

  private final FinanceReportService reportService;

  @GetMapping("/trial-balance")
  public Result<TrialBalanceView> trialBalance(
      @RequestParam long companyId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return Result.ok(reportService.trialBalance(companyId, from, to));
  }

  @GetMapping("/ap-reconciliation")
  public Result<ApReconciliationView> apReconciliation(
      @RequestParam long companyId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    return Result.ok(reportService.apReconciliation(companyId, asOf));
  }

  @GetMapping("/inventory-reconciliation")
  public Result<InventoryReconciliationView> inventoryReconciliation(
      @RequestParam long companyId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    return Result.ok(reportService.inventoryReconciliation(companyId, asOf));
  }
}
