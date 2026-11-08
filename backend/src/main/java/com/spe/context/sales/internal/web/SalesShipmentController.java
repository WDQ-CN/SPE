package com.spe.context.sales.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.sales.internal.SalesShipment;
import com.spe.context.sales.internal.SalesShipmentService;
import com.spe.context.sales.internal.SalesShipmentView;
import com.spe.context.sales.internal.web.dto.CreateShipmentRequest;
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

/** 销售发货（docs/erp/35）。 */
@RestController
@RequestMapping("/api/sales/shipments")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class SalesShipmentController {

  private final SalesShipmentService shipmentService;

  @GetMapping
  public Result<PageResult<SalesShipment>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long orderId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(shipmentService.page(page, size, companyId, orderId, status));
  }

  @PostMapping
  public Result<SalesShipmentView> create(
      @Valid @RequestBody CreateShipmentRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        shipmentService.create(
            operator.uid(),
            request.orderId(),
            request.warehouseId(),
            request.lines().stream()
                .map(
                    line ->
                        new SalesShipmentService.ShipmentLineCommand(
                            line.orderLineId(), line.qty(), line.lotNo(), line.locationId()))
                .toList()));
  }

  @GetMapping("/{id}")
  public Result<SalesShipmentView> detail(@PathVariable long id) {
    return Result.ok(shipmentService.detail(id));
  }
}
