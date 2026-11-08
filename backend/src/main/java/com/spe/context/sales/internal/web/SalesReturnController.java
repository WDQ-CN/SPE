package com.spe.context.sales.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.sales.internal.SalesReturn;
import com.spe.context.sales.internal.SalesReturnService;
import com.spe.context.sales.internal.SalesReturnView;
import com.spe.context.sales.internal.web.dto.CreateSalesReturnRequest;
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

/** 销售退货/贷项（docs/erp/35）。 */
@RestController
@RequestMapping("/api/sales/returns")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class SalesReturnController {

  private final SalesReturnService returnService;

  @GetMapping
  public Result<PageResult<SalesReturn>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long customerId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(returnService.page(page, size, companyId, customerId, status));
  }

  @PostMapping
  public Result<SalesReturnView> create(
      @Valid @RequestBody CreateSalesReturnRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        returnService.create(
            operator.uid(),
            request.shipmentId(),
            request.reason(),
            request.lines().stream()
                .map(
                    line ->
                        new SalesReturnService.ReturnLineCommand(
                            line.shipmentLineId(), line.qty(), line.reason()))
                .toList()));
  }

  @GetMapping("/{id}")
  public Result<SalesReturnView> detail(@PathVariable long id) {
    return Result.ok(returnService.detail(id));
  }

  @PostMapping("/{id}/credit")
  public Result<SalesReturnView> credit(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(returnService.credit(id, operator.uid()));
  }
}
