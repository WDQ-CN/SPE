package com.spe.context.inventory.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.inventory.internal.InventoryAllocationService;
import com.spe.context.inventory.internal.InventoryFreeze;
import com.spe.context.inventory.internal.InventoryReservation;
import com.spe.context.inventory.internal.LocationCapacityService;
import com.spe.context.inventory.internal.LocationCapacityView;
import com.spe.context.inventory.internal.web.dto.CreateFreezeRequest;
import com.spe.context.inventory.internal.web.dto.CreateReservationRequest;
import com.spe.context.inventory.internal.web.dto.UpsertLocationCapacityRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 预留/冻结与库位容量（docs/erp/54 §5.1）。 */
@RestController
@RequestMapping("/api/inventory")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class InventoryAllocationController {

  private final InventoryAllocationService allocationService;
  private final LocationCapacityService capacityService;

  @GetMapping("/reservations")
  public Result<PageResult<InventoryReservation>> listReservations(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long warehouseId,
      @RequestParam(required = false) Long itemId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(allocationService.pageReservations(page, size, warehouseId, itemId, status));
  }

  @PostMapping("/reservations")
  public Result<InventoryReservation> reserve(
      @Valid @RequestBody CreateReservationRequest request) {
    return Result.ok(
        allocationService.reserve(
            request.companyId(),
            request.warehouseId(),
            request.locationId(),
            request.itemId(),
            request.lotId(),
            request.serialId(),
            request.qty(),
            request.sourceType(),
            request.sourceId(),
            request.sourceLineId(),
            request.expiresAt()));
  }

  @PostMapping("/reservations/{id}/release")
  public Result<InventoryReservation> releaseReservation(@PathVariable long id) {
    return Result.ok(allocationService.releaseReservation(id));
  }

  @PostMapping("/reservations/{id}/consume")
  public Result<InventoryReservation> consumeReservation(@PathVariable long id) {
    return Result.ok(allocationService.consumeReservation(id));
  }

  @PostMapping("/reservations/expire")
  public Result<Integer> expireReservations() {
    return Result.ok(allocationService.expireReservations());
  }

  @GetMapping("/freezes")
  public Result<PageResult<InventoryFreeze>> listFreezes(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long warehouseId,
      @RequestParam(required = false) Long itemId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(allocationService.pageFreezes(page, size, warehouseId, itemId, status));
  }

  @PostMapping("/freezes")
  public Result<InventoryFreeze> freeze(@Valid @RequestBody CreateFreezeRequest request) {
    return Result.ok(
        allocationService.freeze(
            request.companyId(),
            request.warehouseId(),
            request.locationId(),
            request.itemId(),
            request.lotId(),
            request.serialId(),
            request.qty(),
            request.reason(),
            request.sourceType(),
            request.sourceId()));
  }

  @PostMapping("/freezes/{id}/release")
  public Result<InventoryFreeze> releaseFreeze(@PathVariable long id) {
    return Result.ok(allocationService.releaseFreeze(id));
  }

  @GetMapping("/locations/{locationId}/capacity")
  public Result<List<LocationCapacityView>> listCapacity(@PathVariable long locationId) {
    return Result.ok(capacityService.list(locationId));
  }

  @PutMapping("/locations/{locationId}/capacity")
  public Result<LocationCapacityView> upsertCapacity(
      @PathVariable long locationId, @Valid @RequestBody UpsertLocationCapacityRequest request) {
    return Result.ok(
        capacityService.upsert(
            locationId,
            request.itemId() == null ? 0L : request.itemId(),
            request.maxQty(),
            request.uom(),
            request.status()));
  }
}
