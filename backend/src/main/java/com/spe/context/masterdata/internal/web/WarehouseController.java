package com.spe.context.masterdata.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.masterdata.internal.OrganizationService;
import com.spe.context.masterdata.internal.WarehouseView;
import com.spe.context.masterdata.internal.web.dto.ChangeMasterDataStatusRequest;
import com.spe.context.masterdata.internal.web.dto.CreateWarehouseRequest;
import com.spe.context.masterdata.internal.web.dto.UpdateWarehouseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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

/** 仓库主数据接口。 */
@RestController
@RequestMapping("/api/masterdata/warehouses")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class WarehouseController {

  private final OrganizationService organizationService;

  @GetMapping
  public Result<PageResult<WarehouseView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Positive(message = "工厂参数不合法") Long plantId,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(organizationService.pageWarehouses(page, size, plantId, keyword, status));
  }

  @PostMapping
  public Result<WarehouseView> create(@Valid @RequestBody CreateWarehouseRequest request) {
    return Result.ok(
        organizationService.createWarehouse(
            request.plantId(), request.code(), request.name(), request.warehouseType()));
  }

  @PutMapping("/{id}")
  public Result<WarehouseView> update(
      @PathVariable long id, @Valid @RequestBody UpdateWarehouseRequest request) {
    return Result.ok(
        organizationService.updateWarehouse(
            id, request.code(), request.name(), request.warehouseType(), request.version()));
  }

  @PutMapping("/{id}/status")
  public Result<Void> changeStatus(
      @PathVariable long id, @Valid @RequestBody ChangeMasterDataStatusRequest request) {
    organizationService.changeWarehouseStatus(id, request.status(), request.version());
    return Result.ok();
  }
}
