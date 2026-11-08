package com.spe.context.masterdata.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.masterdata.internal.ItemService;
import com.spe.context.masterdata.internal.ItemUomView;
import com.spe.context.masterdata.internal.ItemView;
import com.spe.context.masterdata.internal.web.dto.ChangeMasterDataStatusRequest;
import com.spe.context.masterdata.internal.web.dto.CreateItemRequest;
import com.spe.context.masterdata.internal.web.dto.CreateItemUomRequest;
import com.spe.context.masterdata.internal.web.dto.UpdateItemRequest;
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

/** 物料主数据接口（含计量单位换算）。 */
@RestController
@RequestMapping("/api/masterdata/items")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class ItemController {

  private final ItemService itemService;

  @GetMapping
  public Result<PageResult<ItemView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword,
      @RequestParam(required = false) @Size(max = 16, message = "物料类型长度不得超过 16") String itemType,
      @RequestParam(required = false) @Size(max = 16, message = "状态长度不得超过 16") String status) {
    return Result.ok(itemService.pageItems(page, size, keyword, itemType, status));
  }

  @PostMapping
  public Result<ItemView> create(@Valid @RequestBody CreateItemRequest request) {
    return Result.ok(
        itemService.createItem(
            request.code(),
            request.name(),
            request.spec(),
            request.itemType(),
            request.baseUom(),
            request.lotControlled(),
            request.serialControlled(),
            request.valuationMethod(),
            request.standardCost()));
  }

  @PutMapping("/{id}")
  public Result<ItemView> update(@PathVariable long id, @Valid @RequestBody UpdateItemRequest request) {
    return Result.ok(
        itemService.updateItem(
            id,
            request.code(),
            request.name(),
            request.spec(),
            request.itemType(),
            request.baseUom(),
            request.lotControlled(),
            request.serialControlled(),
            request.valuationMethod(),
            request.standardCost(),
            request.version()));
  }

  @PutMapping("/{id}/status")
  public Result<Void> changeStatus(
      @PathVariable long id, @Valid @RequestBody ChangeMasterDataStatusRequest request) {
    itemService.changeItemStatus(id, request.status(), request.version());
    return Result.ok();
  }

  @GetMapping("/{id}/uoms")
  public Result<List<ItemUomView>> listUoms(@PathVariable long id) {
    return Result.ok(itemService.listUoms(id));
  }

  @PostMapping("/{id}/uoms")
  public Result<ItemUomView> addUom(
      @PathVariable long id, @Valid @RequestBody CreateItemUomRequest request) {
    return Result.ok(
        itemService.addUom(
            id, request.fromUom(), request.toUom(), request.numerator(), request.denominator()));
  }

  @DeleteMapping("/{id}/uoms/{uomId}")
  public Result<Void> removeUom(@PathVariable long id, @PathVariable long uomId) {
    itemService.removeUom(id, uomId);
    return Result.ok();
  }
}
