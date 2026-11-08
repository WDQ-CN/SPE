package com.spe.context.manufacturing.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.manufacturing.internal.WorkOrder;
import com.spe.context.manufacturing.internal.WorkOrderService;
import com.spe.context.manufacturing.internal.WorkOrderView;
import com.spe.context.manufacturing.internal.web.dto.CreateWorkOrderRequest;
import com.spe.context.manufacturing.internal.web.dto.IssueWorkOrderRequest;
import com.spe.context.manufacturing.internal.web.dto.ReceiptWorkOrderRequest;
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

/** 生产工单（docs/erp/36）。 */
@RestController
@RequestMapping("/api/manufacturing/work-orders")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class WorkOrderController {

  private final WorkOrderService workOrderService;

  @GetMapping
  public Result<PageResult<WorkOrder>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(workOrderService.page(page, size, companyId, status));
  }

  @PostMapping
  public Result<WorkOrderView> create(
      @Valid @RequestBody CreateWorkOrderRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        workOrderService.create(
            operator.uid(),
            request.companyId(),
            request.plantId(),
            request.itemId(),
            request.bomId(),
            request.qty()));
  }

  @GetMapping("/{id}")
  public Result<WorkOrderView> detail(@PathVariable long id) {
    return Result.ok(workOrderService.detail(id));
  }

  @PostMapping("/{id}/release")
  public Result<WorkOrderView> release(@PathVariable long id) {
    return Result.ok(workOrderService.release(id));
  }

  @PostMapping("/{id}/issues")
  public Result<WorkOrderView> issue(
      @PathVariable long id,
      @Valid @RequestBody IssueWorkOrderRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        workOrderService.issue(
            id,
            operator.uid(),
            request.warehouseId(),
            request.lines().stream()
                .map(
                    line ->
                        new WorkOrderService.IssueLineCommand(
                            line.componentId(), line.qty(), line.lotNo(), line.locationId()))
                .toList()));
  }

  @PostMapping("/{id}/receipts")
  public Result<WorkOrderView> receipt(
      @PathVariable long id,
      @Valid @RequestBody ReceiptWorkOrderRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        workOrderService.receipt(
            id,
            operator.uid(),
            request.warehouseId(),
            request.locationId(),
            request.lotNo(),
            request.qty()));
  }

  @PostMapping("/{id}/close")
  public Result<WorkOrderView> close(@PathVariable long id) {
    return Result.ok(workOrderService.close(id));
  }
}
