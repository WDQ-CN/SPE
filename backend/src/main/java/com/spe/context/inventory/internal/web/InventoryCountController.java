package com.spe.context.inventory.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.inventory.internal.InventoryCount;
import com.spe.context.inventory.internal.InventoryCountService;
import com.spe.context.inventory.internal.InventoryCountView;
import com.spe.context.inventory.internal.web.dto.CreateCountRequest;
import com.spe.context.inventory.internal.web.dto.RecordCountsRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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

/** 库存盘点（docs/erp/54 §5.1）。 */
@RestController
@RequestMapping("/api/inventory/counts")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class InventoryCountController {

  private final InventoryCountService countService;

  @GetMapping
  public Result<PageResult<InventoryCount>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long warehouseId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(countService.page(page, size, companyId, warehouseId, status));
  }

  @PostMapping
  public Result<InventoryCountView> create(@Valid @RequestBody CreateCountRequest request) {
    return Result.ok(
        countService.create(
            request.companyId(),
            request.warehouseId(),
            request.itemId(),
            request.lotId(),
            request.locationId(),
            request.reason()));
  }

  @GetMapping("/{id}")
  public Result<InventoryCountView> detail(@PathVariable long id) {
    return Result.ok(countService.detail(id));
  }

  @PutMapping("/{id}/lines")
  public Result<InventoryCountView> recordCounts(
      @PathVariable long id, @Valid @RequestBody RecordCountsRequest request) {
    return Result.ok(
        countService.recordCounts(
            id,
            request.lines().stream()
                .map(
                    line ->
                        new InventoryCountService.CountCommand(
                            line.lineId(), line.countedQty(), line.reason()))
                .toList()));
  }

  @PostMapping("/{id}/submit")
  public Result<InventoryCountView> submit(@PathVariable long id) {
    return Result.ok(countService.submit(id));
  }

  @PostMapping("/{id}/approve")
  public Result<InventoryCountView> approve(@PathVariable long id) {
    return Result.ok(countService.approve(id));
  }

  @PostMapping("/{id}/post")
  public Result<InventoryCountView> post(@PathVariable long id) {
    return Result.ok(countService.post(id));
  }

  @PostMapping("/{id}/cancel")
  public Result<InventoryCountView> cancel(@PathVariable long id) {
    return Result.ok(countService.cancel(id));
  }
}
