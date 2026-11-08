package com.spe.context.masterdata.internal.web;

import com.spe.common.web.Result;
import com.spe.context.masterdata.internal.WarehouseLocationService;
import com.spe.context.masterdata.internal.WarehouseLocationView;
import com.spe.context.masterdata.internal.web.dto.ChangeMasterDataStatusRequest;
import com.spe.context.masterdata.internal.web.dto.CreateWarehouseLocationRequest;
import com.spe.context.masterdata.internal.web.dto.UpdateWarehouseLocationRequest;
import jakarta.validation.Valid;
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

/** 仓库库位接口（docs/erp/31、33）。 */
@RestController
@RequestMapping("/api/masterdata")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class WarehouseLocationController {

  private final WarehouseLocationService locationService;

  @GetMapping("/warehouses/{warehouseId}/locations")
  public Result<List<WarehouseLocationView>> list(
      @PathVariable long warehouseId,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(locationService.list(warehouseId, status));
  }

  @PostMapping("/warehouses/{warehouseId}/locations")
  public Result<WarehouseLocationView> create(
      @PathVariable long warehouseId, @Valid @RequestBody CreateWarehouseLocationRequest request) {
    return Result.ok(
        locationService.create(
            warehouseId,
            request.code(),
            request.name(),
            request.zone(),
            request.aisle(),
            request.rack(),
            request.levelNo(),
            request.binNo()));
  }

  @PutMapping("/locations/{id}")
  public Result<WarehouseLocationView> update(
      @PathVariable long id, @Valid @RequestBody UpdateWarehouseLocationRequest request) {
    return Result.ok(
        locationService.update(
            id,
            request.code(),
            request.name(),
            request.zone(),
            request.aisle(),
            request.rack(),
            request.levelNo(),
            request.binNo(),
            request.version()));
  }

  @PutMapping("/locations/{id}/status")
  public Result<Void> changeStatus(
      @PathVariable long id, @Valid @RequestBody ChangeMasterDataStatusRequest request) {
    locationService.changeStatus(id, request.status(), request.version());
    return Result.ok();
  }
}
