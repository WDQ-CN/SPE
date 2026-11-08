package com.spe.context.inventory.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.inventory.internal.ExpiringLotView;
import com.spe.context.inventory.internal.FefoAllocationView;
import com.spe.context.inventory.internal.InventoryLotService;
import com.spe.context.inventory.internal.InventoryLotView;
import com.spe.context.inventory.internal.web.dto.ChangeLotStatusRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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

/** 批次查询与状态管理（docs/erp/33）。 */
@RestController
@RequestMapping("/api/inventory/lots")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class InventoryLotController {

  private final InventoryLotService lotService;

  @GetMapping
  public Result<PageResult<InventoryLotView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long itemId,
      @RequestParam(required = false) @Size(max = 64, message = "关键字长度不得超过 64") String keyword,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(lotService.page(page, size, itemId, keyword, status));
  }

  @GetMapping("/fefo")
  public Result<List<FefoAllocationView>> fefo(
      @RequestParam long companyId,
      @RequestParam long warehouseId,
      @RequestParam long itemId,
      @RequestParam BigDecimal qty,
      @RequestParam(required = false) Long locationId) {
    return Result.ok(lotService.fefo(companyId, warehouseId, itemId, qty, locationId));
  }

  @GetMapping("/expiring")
  public Result<List<ExpiringLotView>> expiring(
      @RequestParam(defaultValue = "30") int days,
      @RequestParam(required = false) Long warehouseId) {
    return Result.ok(lotService.expiring(days, warehouseId));
  }

  @PostMapping("/expiry-scan")
  public Result<Integer> scanExpiry(@RequestParam(defaultValue = "30") int days) {
    return Result.ok(lotService.scanExpiry(days));
  }

  @PutMapping("/{id}/status")
  public Result<Void> changeStatus(
      @PathVariable long id, @Valid @RequestBody ChangeLotStatusRequest request) {
    lotService.changeStatus(id, request.status(), request.version());
    return Result.ok();
  }
}
