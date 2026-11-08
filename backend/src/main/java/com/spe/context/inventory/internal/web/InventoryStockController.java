package com.spe.context.inventory.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.inventory.internal.InventoryService;
import com.spe.context.inventory.internal.InventoryStockView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 库存余额查询（W3；docs/erp/33、49）。 */
@RestController
@RequestMapping("/api/inventory/stock")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@RequiredArgsConstructor
public class InventoryStockController {

  private final InventoryService inventoryService;

  @GetMapping
  public Result<PageResult<InventoryStockView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long plantId,
      @RequestParam(required = false) Long warehouseId,
      @RequestParam(required = false) Long itemId,
      @RequestParam(required = false) Long lotId,
      @RequestParam(required = false) Long locationId) {
    return Result.ok(
        inventoryService.pageStock(
            page, size, companyId, plantId, warehouseId, itemId, lotId, locationId));
  }
}
