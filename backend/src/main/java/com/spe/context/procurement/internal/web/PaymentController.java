package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.PaymentService;
import com.spe.context.procurement.internal.PaymentSummaryView;
import com.spe.context.procurement.internal.PaymentView;
import com.spe.context.procurement.internal.web.dto.CreatePaymentRequest;
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

/** 付款申请/审批/登记接口（W4；docs/erp/32、49）。 */
@RestController
@RequestMapping("/api/procurement/payments")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class PaymentController {

  private final PaymentService paymentService;

  @GetMapping
  public Result<PageResult<PaymentSummaryView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long invoiceId,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword) {
    return Result.ok(paymentService.page(page, size, companyId, invoiceId, status, keyword));
  }

  @GetMapping("/{id}")
  public Result<PaymentView> detail(@PathVariable long id) {
    return Result.ok(paymentService.detail(id));
  }

  @PostMapping
  public Result<PaymentView> create(
      @Valid @RequestBody CreatePaymentRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        paymentService.create(operator.uid(), request.invoiceId(), request.amount()));
  }

  @PostMapping("/{id}/approve")
  public Result<PaymentView> approve(
      @PathVariable long id,
      @Valid @RequestBody VersionedRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(paymentService.approve(id, operator.uid(), request.version()));
  }

  @PostMapping("/{id}/pay")
  public Result<PaymentView> pay(
      @PathVariable long id,
      @Valid @RequestBody VersionedRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(paymentService.pay(id, operator.uid(), request.version()));
  }
}
