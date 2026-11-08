package com.spe.context.procurement.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.procurement.internal.GoodsReceiptLineCommand;
import com.spe.context.procurement.internal.GoodsReceiptService;
import com.spe.context.procurement.internal.GoodsReceiptSummaryView;
import com.spe.context.procurement.internal.GoodsReceiptView;
import com.spe.context.procurement.internal.web.dto.CreateGoodsReceiptRequest;
import com.spe.context.procurement.internal.web.dto.ReverseGoodsReceiptRequest;
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

/** 收货接口（docs/erp/32、49、52 W2）：过账即更新 PO 已收数量与状态。 */
@RestController
@RequestMapping("/api/procurement/goods-receipts")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class GoodsReceiptController {

  private final GoodsReceiptService goodsReceiptService;

  @GetMapping
  public Result<PageResult<GoodsReceiptSummaryView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long poId,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword) {
    return Result.ok(goodsReceiptService.page(page, size, companyId, poId, keyword));
  }

  @GetMapping("/{id}")
  public Result<GoodsReceiptView> detail(@PathVariable long id) {
    return Result.ok(goodsReceiptService.detail(id));
  }

  @PostMapping
  public Result<GoodsReceiptView> create(
      @Valid @RequestBody CreateGoodsReceiptRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        goodsReceiptService.create(
            operator.uid(),
            request.poId(),
            request.warehouseId(),
            request.lines().stream()
                .map(
                    line ->
                        new GoodsReceiptLineCommand(
                            line.poLineId(),
                            line.qty(),
                            line.lotNo(),
                            line.productionDate(),
                            line.expiryDate(),
                            line.locationId(),
                            line.serials()))
                .toList()));
  }

  @PostMapping("/{id}/reverse")
  public Result<GoodsReceiptView> reverse(
      @PathVariable long id,
      @Valid @RequestBody ReverseGoodsReceiptRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        goodsReceiptService.reverse(id, operator.uid(), request.reason(), request.version()));
  }
}
