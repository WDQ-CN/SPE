package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.InvoiceLineCommand;
import com.spe.context.procurement.internal.InvoiceSummaryView;
import com.spe.context.procurement.internal.InvoiceView;
import com.spe.context.procurement.internal.SupplierInvoiceService;
import com.spe.context.procurement.internal.web.dto.ApproveInvoiceRequest;
import com.spe.context.procurement.internal.web.dto.CreateInvoiceRequest;
import com.spe.context.procurement.internal.web.dto.VersionedRequest;
import jakarta.validation.Valid;
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

/** 供应商发票与三单匹配接口（W4；docs/erp/32、49）。 */
@RestController
@RequestMapping("/api/procurement/invoices")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class SupplierInvoiceController {

  private final SupplierInvoiceService invoiceService;

  @GetMapping
  public Result<PageResult<InvoiceSummaryView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long supplierId,
      @RequestParam(required = false) @Size(max = 24, message = "状态长度不得超过 24") String status,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword) {
    return Result.ok(invoiceService.page(page, size, companyId, supplierId, status, keyword));
  }

  @GetMapping("/{id}")
  public Result<InvoiceView> detail(@PathVariable long id) {
    return Result.ok(invoiceService.detail(id));
  }

  @PostMapping
  public Result<InvoiceView> create(
      @Valid @RequestBody CreateInvoiceRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        invoiceService.create(
            operator.uid(),
            request.poId(),
            request.supplierInvoiceNo(),
            request.invoiceDate(),
            request.dueDate(),
            request.currency(),
            request.taxAmount(),
            request.lines().stream()
                .map(line -> new InvoiceLineCommand(line.poLineId(), line.qty(), line.unitPrice()))
                .toList()));
  }

  @PostMapping("/{id}/match")
  public Result<InvoiceView> rematch(
      @PathVariable long id, @Valid @RequestBody VersionedRequest request) {
    return Result.ok(invoiceService.rematch(id, request.version()));
  }

  @PostMapping("/{id}/approve")
  public Result<InvoiceView> approve(
      @PathVariable long id,
      @Valid @RequestBody ApproveInvoiceRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        invoiceService.approve(id, operator.uid(), request.comment(), request.version()));
  }
}
