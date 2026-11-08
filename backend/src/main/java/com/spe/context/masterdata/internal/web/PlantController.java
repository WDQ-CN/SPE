package com.spe.context.masterdata.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.masterdata.internal.OrganizationService;
import com.spe.context.masterdata.internal.PlantView;
import com.spe.context.masterdata.internal.web.dto.ChangeMasterDataStatusRequest;
import com.spe.context.masterdata.internal.web.dto.CreatePlantRequest;
import com.spe.context.masterdata.internal.web.dto.UpdatePlantRequest;
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

/** 工厂主数据接口。 */
@RestController
@RequestMapping("/api/masterdata/plants")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class PlantController {

  private final OrganizationService organizationService;

  @GetMapping
  public Result<PageResult<PlantView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Positive(message = "公司参数不合法") Long companyId,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(organizationService.pagePlants(page, size, companyId, keyword, status));
  }

  @PostMapping
  public Result<PlantView> create(@Valid @RequestBody CreatePlantRequest request) {
    return Result.ok(
        organizationService.createPlant(
            request.companyId(), request.code(), request.name(), request.address()));
  }

  @PutMapping("/{id}")
  public Result<PlantView> update(@PathVariable long id, @Valid @RequestBody UpdatePlantRequest request) {
    return Result.ok(
        organizationService.updatePlant(
            id, request.code(), request.name(), request.address(), request.version()));
  }

  @PutMapping("/{id}/status")
  public Result<Void> changeStatus(
      @PathVariable long id, @Valid @RequestBody ChangeMasterDataStatusRequest request) {
    organizationService.changePlantStatus(id, request.status(), request.version());
    return Result.ok();
  }
}
