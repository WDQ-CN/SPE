package com.spe.context.inventory.internal.web;

import com.spe.common.web.Result;
import com.spe.context.inventory.internal.InventoryValuationReportView;
import com.spe.context.inventory.internal.InventoryValuationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 库存估值报表（docs/erp/54 §4.7）。 */
@RestController
@RequestMapping("/api/inventory/reports")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class InventoryReportController {

  private final InventoryValuationService valuationService;

  @GetMapping("/valuation")
  public Result<InventoryValuationReportView> valuation(
      @RequestParam long companyId, @RequestParam(required = false) Long warehouseId) {
    return Result.ok(valuationService.report(companyId, warehouseId));
  }
}
