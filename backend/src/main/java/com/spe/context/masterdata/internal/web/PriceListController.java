package com.spe.context.masterdata.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.masterdata.internal.PriceLineView;
import com.spe.context.masterdata.internal.PriceListService;
import com.spe.context.masterdata.internal.PriceListView;
import com.spe.context.masterdata.internal.web.dto.ChangeMasterDataStatusRequest;
import com.spe.context.masterdata.internal.web.dto.CreatePriceLineRequest;
import com.spe.context.masterdata.internal.web.dto.CreatePriceListRequest;
import com.spe.context.masterdata.internal.web.dto.UpdatePriceListRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 价格表与价格行接口。 */
@RestController
@RequestMapping("/api/masterdata/price-lists")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class PriceListController {

  private final PriceListService priceListService;

  @GetMapping
  public Result<PageResult<PriceListView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(priceListService.page(page, size, keyword, status));
  }

  @PostMapping
  public Result<PriceListView> create(@Valid @RequestBody CreatePriceListRequest request) {
    return Result.ok(
        priceListService.create(
            request.code(), request.listType(), request.currency(), request.validFrom(), request.validTo()));
  }

  @PutMapping("/{id}")
  public Result<PriceListView> update(
      @PathVariable long id, @Valid @RequestBody UpdatePriceListRequest request) {
    return Result.ok(
        priceListService.update(
            id,
            request.code(),
            request.currency(),
            request.validFrom(),
            request.validTo(),
            request.version()));
  }

  @PutMapping("/{id}/status")
  public Result<Void> changeStatus(
      @PathVariable long id, @Valid @RequestBody ChangeMasterDataStatusRequest request) {
    priceListService.changeStatus(id, request.status(), request.version());
    return Result.ok();
  }

  @GetMapping("/{id}/lines")
  public Result<List<PriceLineView>> listLines(@PathVariable long id) {
    return Result.ok(priceListService.listLines(id));
  }

  @PostMapping("/{id}/lines")
  public Result<PriceLineView> addLine(
      @PathVariable long id, @Valid @RequestBody CreatePriceLineRequest request) {
    return Result.ok(
        priceListService.addLine(
            id,
            request.itemId(),
            request.uom(),
            request.minQty(),
            request.price(),
            request.taxIncluded(),
            request.validFrom(),
            request.validTo()));
  }

  @DeleteMapping("/{id}/lines/{lineId}")
  public Result<Void> removeLine(@PathVariable long id, @PathVariable long lineId) {
    priceListService.removeLine(id, lineId);
    return Result.ok();
  }
}
