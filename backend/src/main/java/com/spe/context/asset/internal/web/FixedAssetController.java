package com.spe.context.asset.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.asset.internal.FixedAsset;
import com.spe.context.asset.internal.FixedAssetService;
import com.spe.context.asset.internal.FixedAssetView;
import com.spe.context.asset.internal.web.dto.AssetRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 固定资产（docs/erp/38）。 */
@RestController
@RequestMapping("/api/assets")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class FixedAssetController {

  private final FixedAssetService assetService;

  @GetMapping
  public Result<PageResult<FixedAsset>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(assetService.page(page, size, companyId, status));
  }

  @PostMapping
  public Result<FixedAssetView> create(
      @Valid @RequestBody AssetRequests.CreateAsset request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        assetService.create(
            operator.uid(),
            request.companyId(),
            request.plantId(),
            request.category(),
            request.name(),
            request.sourceType(),
            request.sourceId(),
            request.cost(),
            request.salvageValue(),
            request.usefulLifeMonths(),
            request.depreciationMethod()));
  }

  @GetMapping("/{id}")
  public Result<FixedAssetView> detail(@PathVariable long id) {
    return Result.ok(assetService.detail(id));
  }

  @PostMapping("/{id}/capitalize")
  public Result<FixedAssetView> capitalize(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(assetService.capitalize(id, operator.uid()));
  }

  @PostMapping("/{id}/depreciate")
  public Result<FixedAssetView> depreciate(
      @PathVariable long id, @Valid @RequestBody AssetRequests.Depreciate request) {
    return Result.ok(assetService.depreciate(id, request.period()));
  }

  @PostMapping("/{id}/dispose")
  public Result<FixedAssetView> dispose(
      @PathVariable long id, @Valid @RequestBody AssetRequests.Dispose request) {
    return Result.ok(assetService.dispose(id, request.proceeds()));
  }
}
