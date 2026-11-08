package com.spe.context.sales.internal.web;

import com.spe.common.web.Result;
import com.spe.context.sales.internal.ArAgingView;
import com.spe.context.sales.internal.SalesInvoiceService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 销售报表（docs/erp/35 §8）。 */
@RestController
@RequestMapping("/api/sales/reports")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class SalesReportController {

  private final SalesInvoiceService invoiceService;

  @GetMapping("/ar-aging")
  public Result<ArAgingView> arAging(
      @RequestParam long companyId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOf) {
    return Result.ok(invoiceService.arAging(companyId, asOf));
  }
}
