package com.spe.context.inventory.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.inventory.internal.InventoryTransfer;
import com.spe.context.inventory.internal.InventoryTransferService;
import com.spe.context.inventory.internal.InventoryTransferView;
import com.spe.context.inventory.internal.web.dto.CreateTransferRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 库存调拨（docs/erp/54 §5.1）。 */
@RestController
@RequestMapping("/api/inventory/transfers")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class InventoryTransferController {

  private final InventoryTransferService transferService;

  @GetMapping
  public Result<PageResult<InventoryTransfer>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(transferService.page(page, size, companyId, status));
  }

  @PostMapping
  public Result<InventoryTransferView> create(@Valid @RequestBody CreateTransferRequest request) {
    return Result.ok(
        transferService.create(
            request.companyId(),
            request.fromWarehouseId(),
            request.fromLocationId(),
            request.toWarehouseId(),
            request.toLocationId(),
            request.reason(),
            request.lines().stream()
                .map(
                    line ->
                        new InventoryTransferService.LineCommand(
                            line.itemId(),
                            line.lotId(),
                            line.qty(),
                            line.uom(),
                            line.unitCost(),
                            line.serialIds()))
                .toList()));
  }

  @GetMapping("/{id}")
  public Result<InventoryTransferView> detail(@PathVariable long id) {
    return Result.ok(transferService.detail(id));
  }

  @PostMapping("/{id}/ship")
  public Result<InventoryTransferView> ship(@PathVariable long id) {
    return Result.ok(transferService.ship(id));
  }

  @PostMapping("/{id}/receive")
  public Result<InventoryTransferView> receive(@PathVariable long id) {
    return Result.ok(transferService.receive(id));
  }

  @PostMapping("/{id}/cancel")
  public Result<InventoryTransferView> cancel(@PathVariable long id) {
    return Result.ok(transferService.cancel(id));
  }
}
