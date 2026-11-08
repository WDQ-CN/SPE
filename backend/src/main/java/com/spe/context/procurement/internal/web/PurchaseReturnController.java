package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.PurchaseReturnLineCommand;
import com.spe.context.procurement.internal.PurchaseReturnService;
import com.spe.context.procurement.internal.PurchaseReturnSummaryView;
import com.spe.context.procurement.internal.PurchaseReturnView;
import com.spe.context.procurement.internal.web.dto.CreatePurchaseReturnRequest;
import jakarta.validation.Valid;
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

/** 采购退货接口（W3；docs/erp/32 §5、49）。 */
@RestController
@RequestMapping("/api/procurement/returns")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class PurchaseReturnController {

  private final PurchaseReturnService returnService;

  @GetMapping
  public Result<PageResult<PurchaseReturnSummaryView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long poId,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword) {
    return Result.ok(returnService.page(page, size, companyId, poId, keyword));
  }

  @GetMapping("/{id}")
  public Result<PurchaseReturnView> detail(@PathVariable long id) {
    return Result.ok(returnService.detail(id));
  }

  @PostMapping
  public Result<PurchaseReturnView> create(
      @Valid @RequestBody CreatePurchaseReturnRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        returnService.create(
            operator.uid(),
            request.poId(),
            request.warehouseId(),
            request.returnDate(),
            request.reason(),
            request.lines().stream()
                .map(
                    line ->
                        new PurchaseReturnLineCommand(
                            line.poLineId(), line.qty(), line.lotNo(), line.locationId(), line.serials()))
                .toList()));
  }
}
