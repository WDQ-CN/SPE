package com.spe.context.inventory.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.inventory.api.InventoryStockValuation;
import com.spe.context.inventory.api.InventoryValuationFacade;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.ValuationMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存估值（docs/erp/54 §3.6/§4.7）。
 *
 * <p>AVCO 沿用库存余额；FIFO/STANDARD 建立估值层，出库按层序消耗。库存层只由库存移动驱动，
 * 保证子账可追溯到移动。
 */
@Service
@RequiredArgsConstructor
public class InventoryValuationService implements InventoryValuationFacade {

  private final InventoryValuationLayerMapper layerMapper;
  private final InventoryStockMapper stockMapper;
  private final InventoryLotMapper lotMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;

  /** 入库建层；AVCO 不建层。重复移动直接跳过（来源唯一键）。 */
  @Transactional
  public void recordInbound(InventoryMovement movement) {
    ValuationMethod method = valuationMethod(movement.getItemId()).orElse(ValuationMethod.AVCO);
    if (method == ValuationMethod.AVCO) {
      return;
    }
    if (layerMapper.findBySourceMovement(movement.getId()).isPresent()) {
      return;
    }
    BigDecimal unitCost = movement.getUnitCost() == null ? BigDecimal.ZERO : movement.getUnitCost();
    if (method == ValuationMethod.STANDARD) {
      ItemBrief item = requireItem(movement.getItemId());
      if (item.standardCost() == null || item.standardCost().signum() <= 0) {
        throw new BizException(
            ErrorCode.INVENTORY_VALUATION_NOT_CONFIGURED, "标准成本物料未配置标准成本");
      }
      unitCost = item.standardCost();
    }
    BigDecimal amount = unitCost.multiply(movement.getQty()).setScale(4, RoundingMode.HALF_UP);
    InventoryValuationLayer layer = new InventoryValuationLayer();
    layer.setCompanyId(movement.getCompanyId());
    layer.setWarehouseId(movement.getWarehouseId());
    layer.setItemId(movement.getItemId());
    layer.setLotId(movement.getLotId() == null ? 0L : movement.getLotId());
    layer.setMethod(method);
    layer.setSourceMovementId(movement.getId());
    layer.setLayerDate((movement.getOccurredAt() == null
            ? java.time.LocalDateTime.now()
            : movement.getOccurredAt())
        .toLocalDate());
    layer.setQty(movement.getQty());
    layer.setRemainingQty(movement.getQty());
    layer.setUnitCost(unitCost);
    layer.setAmount(amount);
    layer.setRemainingAmount(amount);
    layer.setStatus(ValuationLayerStatus.OPEN);
    try {
      layerMapper.insert(layer);
    } catch (DuplicateKeyException ex) {
      // 唯一键兜底：并发重复事件不再建第二层
    }
  }

  /** 出库消耗 FIFO/STANDARD 层；层不足直接失败，不允许负估值层。 */
  @Transactional
  public void recordOutbound(InventoryMovement movement) {
    ValuationMethod method = valuationMethod(movement.getItemId()).orElse(ValuationMethod.AVCO);
    if (method == ValuationMethod.AVCO) {
      return;
    }
    BigDecimal remaining = movement.getQty();
    List<InventoryValuationLayer> layers =
        layerMapper.lockOpenLayers(
            movement.getCompanyId(),
            movement.getWarehouseId(),
            movement.getItemId(),
            movement.getLotId() == null || movement.getLotId() == 0 ? null : movement.getLotId());
    for (InventoryValuationLayer layer : layers) {
      if (remaining.signum() <= 0) {
        break;
      }
      BigDecimal take = remaining.min(layer.getRemainingQty());
      BigDecimal amount =
          take.multiply(layer.getUnitCost()).setScale(4, RoundingMode.HALF_UP);
      if (amount.compareTo(layer.getRemainingAmount()) > 0) {
        amount = layer.getRemainingAmount();
      }
      if (layerMapper.consume(layer.getId(), take, amount) == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "估值层已被并发消耗");
      }
      remaining = remaining.subtract(take);
    }
    if (remaining.signum() > 0) {
      throw new BizException(
          ErrorCode.INVENTORY_VALUATION_NOT_CONFIGURED, "FIFO 估值层不足，无法完成出库");
    }
  }

  @Transactional(readOnly = true)
  public InventoryValuationReportView report(long companyId, Long warehouseId) {
    List<InventoryStockValuation> valuations = valuations(companyId);
    Map<Long, ItemBrief> items =
        masterDataQueryFacade
            .findItems(valuations.stream().map(InventoryStockValuation::itemId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    java.util.List<Long> lotIds =
        valuations.stream()
            .map(InventoryStockValuation::lotId)
            .filter(id -> id > 0)
            .distinct()
            .toList();
    Map<Long, InventoryLot> lots =
        lotIds.isEmpty()
            ? Map.of()
            : lotMapper.findByIds(lotIds).stream()
                .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));
    List<InventoryValuationRowView> rows =
        valuations.stream()
            .filter(row -> warehouseId == null || row.warehouseId() == warehouseId)
            .map(
                row -> {
                  ItemBrief item = items.get(row.itemId());
                  InventoryLot lot = lots.get(row.lotId());
                  return new InventoryValuationRowView(
                      row.warehouseId(),
                      row.itemId(),
                      item == null ? null : item.code(),
                      item == null ? null : item.name(),
                      row.lotId(),
                      lot == null ? null : lot.getLotNo(),
                      row.valuationMethod() == null
                          ? null
                          : ValuationMethod.valueOf(row.valuationMethod()),
                      row.qty(),
                      row.value());
                })
            .toList();
    BigDecimal total = rows.stream()
        .map(InventoryValuationRowView::value)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new InventoryValuationReportView(companyId, warehouseId, rows, total);
  }

  @Override
  @Transactional(readOnly = true)
  public List<InventoryStockValuation> valuations(long companyId) {
    List<InventoryStock> stocks = stockMapper.findByCompany(companyId);
    if (stocks.isEmpty()) {
      return List.of();
    }
    Map<Long, ItemBrief> items =
        masterDataQueryFacade
            .findItems(stocks.stream().map(InventoryStock::getItemId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    Map<String, InventoryValuationLayerMapper.LayerTotal> layers =
        new LinkedHashMap<>();
    for (InventoryValuationLayerMapper.LayerTotal total : layerMapper.totalsByItemLot(companyId)) {
      layers.put(key(total.warehouseId(), total.itemId(), total.lotId()), total);
    }
    Map<String, ValuationAccumulator> grouped = new LinkedHashMap<>();
    for (InventoryStock stock : stocks) {
      ItemBrief item = items.get(stock.getItemId());
      ValuationMethod method =
          item == null || item.valuationMethod() == null
              ? ValuationMethod.AVCO
              : item.valuationMethod();
      long lotId = stock.getLotId() == null ? 0L : stock.getLotId();
      String key = key(stock.getWarehouseId(), stock.getItemId(), lotId);
      BigDecimal value;
      if (method == ValuationMethod.FIFO) {
        InventoryValuationLayerMapper.LayerTotal total = layers.get(key);
        value =
            total == null
                ? stock.getTotalValue()
                : total.remainingAmount();
      } else if (method == ValuationMethod.STANDARD) {
        BigDecimal standard =
            item == null || item.standardCost() == null ? BigDecimal.ZERO : item.standardCost();
        value = stock.getQtyOnHand().multiply(standard).setScale(4, RoundingMode.HALF_UP);
      } else {
        value = stock.getTotalValue();
      }
      ValuationAccumulator accumulator =
          grouped.computeIfAbsent(
              key,
              ignored ->
                  new ValuationAccumulator(
                      stock.getWarehouseId(), stock.getItemId(), lotId, method));
      accumulator.qty = accumulator.qty.add(stock.getQtyOnHand());
      accumulator.value = accumulator.value.add(value);
    }
    return grouped.values().stream()
        .map(
            acc ->
                new InventoryStockValuation(
                    companyId,
                    acc.warehouseId,
                    acc.itemId,
                    acc.lotId,
                    acc.method.name(),
                    acc.qty,
                    acc.value))
        .toList();
  }

  private java.util.Optional<ValuationMethod> valuationMethod(long itemId) {
    return masterDataQueryFacade
        .findItem(itemId)
        .map(ItemBrief::valuationMethod)
        .filter(Objects::nonNull);
  }

  private ItemBrief requireItem(long itemId) {
    return masterDataQueryFacade
        .findItem(itemId)
        .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
  }

  private static String key(long warehouseId, long itemId, long lotId) {
    return warehouseId + ":" + itemId + ":" + lotId;
  }

  private static final class ValuationAccumulator {
    private final long warehouseId;
    private final long itemId;
    private final long lotId;
    private final ValuationMethod method;
    private BigDecimal qty = BigDecimal.ZERO;
    private BigDecimal value = BigDecimal.ZERO;

    private ValuationAccumulator(
        long warehouseId, long itemId, long lotId, ValuationMethod method) {
      this.warehouseId = warehouseId;
      this.itemId = itemId;
      this.lotId = lotId;
      this.method = method;
    }
  }
}
