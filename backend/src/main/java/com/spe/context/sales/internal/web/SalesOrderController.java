package com.spe.context.sales.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.sales.internal.SalesLineCommand;
import com.spe.context.sales.internal.SalesOrder;
import com.spe.context.sales.internal.SalesOrderService;
import com.spe.context.sales.internal.SalesOrderView;
import com.spe.context.sales.internal.web.dto.CreateSalesOrderRequest;
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

/** 销售订单（docs/erp/35）。 */
@RestController
@RequestMapping("/api/sales/orders")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class SalesOrderController {

  private final SalesOrderService orderService;

  @GetMapping
  public Result<PageResult<SalesOrder>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long customerId,
      @RequestParam(required = false) @Size(max = 24) String status) {
    return Result.ok(orderService.page(page, size, companyId, customerId, status));
  }

  @PostMapping
  public Result<SalesOrderView> create(
      @Valid @RequestBody CreateSalesOrderRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        orderService.create(
            operator.uid(),
            request.companyId(),
            request.customerId(),
            request.currency(),
            request.paymentTerms(),
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
  public Result<SalesOrderView> detail(@PathVariable long id) {
    return Result.ok(orderService.detail(id));
  }

  @PostMapping("/{id}/confirm")
  public Result<SalesOrderView> confirm(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(orderService.confirm(id, operator.uid()));
  }

  @PostMapping("/{id}/cancel")
  public Result<SalesOrderView> cancel(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(orderService.cancel(id, operator.uid()));
  }
}
