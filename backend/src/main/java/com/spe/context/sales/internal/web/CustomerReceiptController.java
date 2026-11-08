package com.spe.context.sales.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.sales.internal.CustomerReceipt;
import com.spe.context.sales.internal.CustomerReceiptService;
import com.spe.context.sales.internal.CustomerReceiptView;
import com.spe.context.sales.internal.web.dto.CreateCustomerReceiptRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** 客户收款与核销（docs/erp/35）。 */
@RestController
@RequestMapping("/api/sales/receipts")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class CustomerReceiptController {

  private final CustomerReceiptService receiptService;

  @GetMapping
  public Result<PageResult<CustomerReceipt>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long customerId) {
    return Result.ok(receiptService.page(page, size, companyId, customerId));
  }

  @PostMapping
  public Result<CustomerReceiptView> create(
      @Valid @RequestBody CreateCustomerReceiptRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        receiptService.create(
            operator.uid(),
            request.customerId(),
            request.method(),
            request.allocations().stream()
                .map(
                    allocation ->
                        new CustomerReceiptService.AllocationCommand(
                            allocation.invoiceId(), allocation.amount()))
                .toList()));
  }

  @GetMapping("/{id}")
  public Result<CustomerReceiptView> detail(@PathVariable long id) {
    return Result.ok(receiptService.detail(id));
  }
}
