package com.spe.context.inventory.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.inventory.internal.InventoryMovementView;
import com.spe.context.inventory.internal.InventoryService;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 库存移动台账查询（W3；docs/erp/33、49）。 */
@RestController
@RequestMapping("/api/inventory/movements")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class InventoryMovementController {

  private final InventoryService inventoryService;

  @GetMapping
  public Result<PageResult<InventoryMovementView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long warehouseId,
      @RequestParam(required = false) Long itemId,
      @RequestParam(required = false) Long lotId,
      @RequestParam(required = false) Long locationId,
      @RequestParam(required = false) @Size(max = 32, message = "来源类型长度不得超过 32") String sourceType,
      @RequestParam(required = false) Long sourceId) {
    return Result.ok(
        inventoryService.pageMovements(
            page, size, companyId, warehouseId, itemId, lotId, locationId, sourceType, sourceId));
  }
}
