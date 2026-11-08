package com.spe.context.quality.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.quality.internal.InspectionOrder;
import com.spe.context.quality.internal.InspectionOrderView;
import com.spe.context.quality.internal.InspectionPlan;
import com.spe.context.quality.internal.InspectionPlanView;
import com.spe.context.quality.internal.InspectionService;
import com.spe.context.quality.internal.web.dto.QualityRequests;
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

/** 检验计划与检验单（docs/erp/37）。 */
@RestController
@RequestMapping("/api/quality")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class QualityInspectionController {

  private final InspectionService inspectionService;

  @GetMapping("/inspection-plans")
  public Result<PageResult<InspectionPlan>> plans(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam long companyId) {
    return Result.ok(inspectionService.pagePlans(page, size, companyId));
  }

  @PostMapping("/inspection-plans")
  public Result<InspectionPlanView> createPlan(
      @Valid @RequestBody QualityRequests.CreateInspectionPlan request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        inspectionService.createPlan(
            operator.uid(),
            request.companyId(),
            request.itemId(),
            request.supplierId(),
            request.inspectionType(),
            request.version(),
            request.samplingPlan(),
            request.aql(),
            request.characteristics().stream()
                .map(
                    c ->
                        new InspectionService.CharacteristicCommand(
                            c.name(), c.spec(), c.critical(), c.method()))
                .toList()));
  }

  @GetMapping("/inspection-plans/{id}")
  public Result<InspectionPlanView> planDetail(@PathVariable long id) {
    return Result.ok(inspectionService.planDetail(id));
  }

  @PostMapping("/inspection-plans/{id}/activate")
  public Result<InspectionPlanView> activatePlan(@PathVariable long id) {
    return Result.ok(inspectionService.activatePlan(id));
  }

  @GetMapping("/inspection-orders")
  public Result<PageResult<InspectionOrder>> orders(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(inspectionService.pageOrders(page, size, companyId, status));
  }

  @PostMapping("/inspection-orders")
  public Result<InspectionOrderView> createOrder(
      @Valid @RequestBody QualityRequests.CreateInspectionOrder request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        inspectionService.createOrder(
            operator.uid(),
            request.companyId(),
            request.planId(),
            request.sourceType(),
            request.sourceId(),
            request.sourceLineId(),
            request.itemId(),
            request.supplierId(),
            request.lotNo(),
            request.qty()));
  }

  @GetMapping("/inspection-orders/{id}")
  public Result<InspectionOrderView> orderDetail(@PathVariable long id) {
    return Result.ok(inspectionService.orderDetail(id));
  }

  @PostMapping("/inspection-orders/{id}/results")
  public Result<InspectionOrderView> recordResults(
      @PathVariable long id, @Valid @RequestBody QualityRequests.RecordResults request) {
    return Result.ok(
        inspectionService.recordResults(
            id,
            request.lines().stream()
                .map(
                    line ->
                        new InspectionService.ResultCommand(
                            line.characteristicId(),
                            line.name(),
                            line.measuredValue(),
                            line.result(),
                            line.remark()))
                .toList()));
  }
}
