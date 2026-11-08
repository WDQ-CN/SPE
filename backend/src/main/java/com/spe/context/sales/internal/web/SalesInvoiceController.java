package com.spe.context.sales.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.sales.internal.SalesInvoice;
import com.spe.context.sales.internal.SalesInvoiceService;
import com.spe.context.sales.internal.SalesInvoiceView;
import com.spe.context.sales.internal.web.dto.CreateSalesInvoiceRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 销售发票（docs/erp/35）。 */
@RestController
@RequestMapping("/api/sales/invoices")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class SalesInvoiceController {

  private final SalesInvoiceService invoiceService;

  @GetMapping
  public Result<PageResult<SalesInvoice>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long customerId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(invoiceService.page(page, size, companyId, customerId, status));
  }

  @PostMapping
  public Result<SalesInvoiceView> create(
      @Valid @RequestBody CreateSalesInvoiceRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        invoiceService.create(
            operator.uid(),
            request.orderId(),
            request.dueDate(),
            request.remark(),
            request.lines().stream()
                .map(
                    line ->
                        new SalesInvoiceService.InvoiceLineCommand(
                            line.orderLineId(), line.qty()))
                .toList()));
  }

  @GetMapping("/{id}")
  public Result<SalesInvoiceView> detail(@PathVariable long id) {
    return Result.ok(invoiceService.detail(id));
  }
}
