package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.PurchaseOrderService;
import com.spe.context.procurement.internal.PurchaseOrderView;
import com.spe.context.procurement.internal.RequisitionLineCommand;
import com.spe.context.procurement.internal.RequisitionService;
import com.spe.context.procurement.internal.RequisitionSummaryView;
import com.spe.context.procurement.internal.RequisitionView;
import com.spe.context.procurement.internal.web.dto.ConvertRequisitionRequest;
import com.spe.context.procurement.internal.web.dto.CreateRequisitionRequest;
import com.spe.context.procurement.internal.web.dto.RejectRequest;
import com.spe.context.procurement.internal.web.dto.UpdateRequisitionRequest;
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

/** 请购单接口（docs/erp/32、49、52 W2）。 */
@RestController
@RequestMapping("/api/procurement/requisitions")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class RequisitionController {

  private final RequisitionService requisitionService;
  private final PurchaseOrderService purchaseOrderService;

  @GetMapping
  public Result<PageResult<RequisitionSummaryView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword) {
    return Result.ok(requisitionService.page(page, size, companyId, status, keyword));
  }

  @GetMapping("/{id}")
  public Result<RequisitionView> detail(@PathVariable long id) {
    return Result.ok(requisitionService.detail(id));
  }

  @PostMapping
  public Result<RequisitionView> create(
      @Valid @RequestBody CreateRequisitionRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        requisitionService.create(
            operator.uid(),
            request.companyId(),
            request.needDate(),
            request.currency(),
            request.lines().stream()
                .map(
                    line ->
                        new RequisitionLineCommand(
                            line.itemId(), line.qty(), line.uom(), line.estimatedPrice()))
                .toList()));
  }

  @PutMapping("/{id}")
  public Result<RequisitionView> update(
      @PathVariable long id,
      @Valid @RequestBody UpdateRequisitionRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        requisitionService.update(
            id,
            operator.uid(),
            request.needDate(),
            request.currency(),
            request.lines().stream()
                .map(
                    line ->
                        new RequisitionLineCommand(
                            line.itemId(), line.qty(), line.uom(), line.estimatedPrice()))
                .toList(),
            request.version()));
  }

  @PostMapping("/{id}/submit")
  public Result<RequisitionView> submit(
      @PathVariable long id,
      @Valid @RequestBody VersionedRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(requisitionService.submit(id, operator.uid(), request.version()));
  }

  @PostMapping("/{id}/approve")
  public Result<RequisitionView> approve(
      @PathVariable long id,
      @Valid @RequestBody VersionedRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    return Result.ok(
        requisitionService.approve(id, operator.uid(), ProcurementWebSupport.isAdmin(authentication), request.version()));
  }

  @PostMapping("/{id}/reject")
  public Result<RequisitionView> reject(
      @PathVariable long id,
      @Valid @RequestBody RejectRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    return Result.ok(
        requisitionService.reject(
            id,
            operator.uid(),
            ProcurementWebSupport.isAdmin(authentication),
            request.comment(),
            request.version()));
  }

  @PostMapping("/{id}/convert")
  public Result<PurchaseOrderView> convert(
      @PathVariable long id,
      @Valid @RequestBody ConvertRequisitionRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        purchaseOrderService.createFromRequisition(
            id,
            operator.uid(),
            request.supplierId(),
            request.plantId(),
            request.paymentTerms(),
            request.version()));
  }
}
