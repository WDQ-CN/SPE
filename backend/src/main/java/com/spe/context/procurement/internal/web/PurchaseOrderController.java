package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.PurchaseOrderLineCommand;
import com.spe.context.procurement.internal.PurchaseOrderService;
import com.spe.context.procurement.internal.PurchaseOrderSummaryView;
import com.spe.context.procurement.internal.PurchaseOrderView;
import com.spe.context.procurement.internal.web.dto.CreatePurchaseOrderRequest;
import com.spe.context.procurement.internal.web.dto.RejectRequest;
import com.spe.context.procurement.internal.web.dto.UpdatePurchaseOrderRequest;
import com.spe.context.procurement.internal.web.dto.VersionedRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 采购订单接口（docs/erp/32、49、52 W2）。 */
@RestController
@RequestMapping("/api/procurement/purchase-orders")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class PurchaseOrderController {

  private final PurchaseOrderService purchaseOrderService;

  @GetMapping
  public Result<PageResult<PurchaseOrderSummaryView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long supplierId,
      @RequestParam(required = false) @Size(max = 24, message = "状态长度不得超过 24") String status,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword) {
    return Result.ok(
        purchaseOrderService.page(page, size, companyId, supplierId, status, keyword));
  }

  @GetMapping("/{id}")
  public Result<PurchaseOrderView> detail(@PathVariable long id) {
    return Result.ok(purchaseOrderService.detail(id));
  }

  @PostMapping
  public Result<PurchaseOrderView> create(
      @Valid @RequestBody CreatePurchaseOrderRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        purchaseOrderService.create(
            operator.uid(),
            request.companyId(),
            request.supplierId(),
            request.plantId(),
            request.currency(),
            request.paymentTerms(),
            request.lines().stream()
                .map(
                    line ->
                        new PurchaseOrderLineCommand(
                            line.itemId(), line.qty(), line.uom(), line.price()))
                .toList()));
  }

  @PutMapping("/{id}")
  public Result<PurchaseOrderView> update(
      @PathVariable long id,
      @Valid @RequestBody UpdatePurchaseOrderRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        purchaseOrderService.update(
            id,
            operator.uid(),
            request.supplierId(),
            request.plantId(),
            request.currency(),
            request.paymentTerms(),
            request.lines().stream()
                .map(
                    line ->
                        new PurchaseOrderLineCommand(
                            line.itemId(), line.qty(), line.uom(), line.price()))
                .toList(),
            request.version()));
  }

  @PostMapping("/{id}/approve")
  public Result<PurchaseOrderView> approve(
      @PathVariable long id,
      @Valid @RequestBody VersionedRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    return Result.ok(
        purchaseOrderService.approve(
            id, operator.uid(), ProcurementWebSupport.isAdmin(authentication), request.version()));
  }

  @PostMapping("/{id}/reject")
  public Result<PurchaseOrderView> reject(
      @PathVariable long id,
      @Valid @RequestBody RejectRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    return Result.ok(
        purchaseOrderService.reject(
            id,
            operator.uid(),
            ProcurementWebSupport.isAdmin(authentication),
            request.comment(),
            request.version()));
  }

  @PostMapping("/{id}/send")
  public Result<PurchaseOrderView> send(
      @PathVariable long id, @Valid @RequestBody VersionedRequest request) {
    return Result.ok(purchaseOrderService.send(id, request.version()));
  }

  @PostMapping("/{id}/cancel")
  public Result<PurchaseOrderView> cancel(
      @PathVariable long id, @Valid @RequestBody VersionedRequest request) {
    return Result.ok(purchaseOrderService.cancel(id, request.version()));
  }
}
