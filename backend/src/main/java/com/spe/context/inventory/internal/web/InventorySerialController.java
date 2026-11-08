package com.spe.context.inventory.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.inventory.internal.InventorySerialService;
import com.spe.context.inventory.internal.InventorySerialView;
import com.spe.context.inventory.internal.SerialTraceView;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 序列号查询与追溯（docs/erp/54）。 */
@RestController
@RequestMapping("/api/inventory/serials")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class InventorySerialController {

  private final InventorySerialService serialService;

  @GetMapping
  public Result<PageResult<InventorySerialView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long itemId,
      @RequestParam(required = false) Long lotId,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status,
      @RequestParam(required = false) @Size(max = 64, message = "关键字长度不得超过 64") String keyword) {
    return Result.ok(serialService.page(page, size, itemId, lotId, status, keyword));
  }

  @GetMapping("/{id}/trace")
  public Result<SerialTraceView> trace(@PathVariable long id) {
    return Result.ok(serialService.trace(id));
  }
}
