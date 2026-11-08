package com.spe.context.manufacturing.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.manufacturing.internal.BomHeader;
import com.spe.context.manufacturing.internal.BomService;
import com.spe.context.manufacturing.internal.BomView;
import com.spe.context.manufacturing.internal.web.dto.CreateBomRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** BOM（docs/erp/36）。 */
@RestController
@RequestMapping("/api/manufacturing/boms")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class BomController {

  private final BomService bomService;

  @GetMapping
  public Result<PageResult<BomHeader>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam long companyId) {
    return Result.ok(bomService.page(page, size, companyId));
  }

  @PostMapping
  public Result<BomView> create(
      @Valid @RequestBody CreateBomRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        bomService.create(
            operator.uid(),
            request.companyId(),
            request.itemId(),
            request.version(),
            request.lines().stream()
                .map(
                    line ->
                        new BomService.BomLineCommand(
                            line.componentItemId(), line.qty(), line.uom()))
                .toList()));
  }

  @GetMapping("/{id}")
  public Result<BomView> detail(@PathVariable long id) {
    return Result.ok(bomService.detail(id));
  }

  @PostMapping("/{id}/activate")
  public Result<BomView> activate(@PathVariable long id) {
    return Result.ok(bomService.activate(id));
  }
}
