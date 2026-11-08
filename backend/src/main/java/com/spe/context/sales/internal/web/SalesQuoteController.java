package com.spe.context.sales.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.sales.internal.SalesLineCommand;
import com.spe.context.sales.internal.SalesOrderView;
import com.spe.context.sales.internal.SalesQuote;
import com.spe.context.sales.internal.SalesQuoteService;
import com.spe.context.sales.internal.SalesQuoteView;
import com.spe.context.sales.internal.web.dto.CreateSalesQuoteRequest;
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

/** 销售报价（docs/erp/35）。 */
@RestController
@RequestMapping("/api/sales/quotes")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class SalesQuoteController {

  private final SalesQuoteService quoteService;

  @GetMapping
  public Result<PageResult<SalesQuote>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long customerId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(quoteService.page(page, size, companyId, customerId, status));
  }

  @PostMapping
  public Result<SalesQuoteView> create(
      @Valid @RequestBody CreateSalesQuoteRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        quoteService.create(
            operator.uid(),
            request.companyId(),
            request.customerId(),
            request.currency(),
            request.validUntil(),
            request.lines().stream()
                .map(
                    line ->
                        new SalesLineCommand(
                            line.itemId(),
                            line.qty(),
                            line.uom(),
                            line.unitPrice(),
                            line.discountRate(),
                            line.taxRate()))
                .toList()));
  }

  @GetMapping("/{id}")
  public Result<SalesQuoteView> detail(@PathVariable long id) {
    return Result.ok(quoteService.detail(id));
  }

  @PostMapping("/{id}/send")
  public Result<SalesQuoteView> send(@PathVariable long id) {
    return Result.ok(quoteService.send(id));
  }

  @PostMapping("/{id}/accept")
  public Result<SalesOrderView> accept(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(quoteService.accept(id, operator.uid()));
  }
}
