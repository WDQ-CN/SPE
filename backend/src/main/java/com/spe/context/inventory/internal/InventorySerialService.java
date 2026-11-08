package com.spe.context.inventory.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.sql.SqlKeywords;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.LocationBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.WarehouseBrief;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 序列号（V1015）：收货建档、冲销/退货状态、列表与追溯。 */
@Service
@RequiredArgsConstructor
public class InventorySerialService {

  private final InventorySerialMapper serialMapper;
  private final InventoryMovementSerialMapper movementSerialMapper;
  private final InventoryLotMapper lotMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SupplierQueryFacade supplierQueryFacade;

  private static final String SOURCE_GOODS_RECEIPT = "GOODS_RECEIPT";

  @Transactional
  public long registerForReceipt(
      long itemId,
      long lotId,
      String rawSerialNo,
      Long supplierId,
      String sourceType,
      long sourceId,
      long sourceLineId,
      long warehouseId,
      long locationId) {
    String serialNo = normalize(rawSerialNo);
    InventorySerial existing = serialMapper.findByItemAndSerial(itemId, serialNo).orElse(null);
    if (existing != null) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_DUPLICATE, "序列号已存在：" + serialNo);
    }
    InventorySerial serial = new InventorySerial();
    serial.setSerialNo(serialNo);
    serial.setItemId(itemId);
    serial.setLotId(lotId);
    serial.setSupplierId(supplierId);
    serial.setSourceType(sourceType);
    serial.setSourceId(sourceId);
    serial.setSourceLineId(sourceLineId);
    serial.setWarehouseId(warehouseId);
    serial.setLocationId(locationId);
    serial.setStatus(InventorySerialStatus.IN_STOCK);
    serial.setVersion(0);
    try {
      serialMapper.insert(serial);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_DUPLICATE, "序列号已存在：" + serialNo);
    }
    return serial.getId();
  }

  /** 冲销/退货：按来源行找当前在库序列并置 RETURNED，返回可关联 OUT 移动的序列。 */
  @Transactional
  public List<InventorySerial> markReturnedBySource(
      String sourceType, long sourceId, long sourceLineId) {
    List<InventorySerial> serials =
        sourceType.equals(SOURCE_GOODS_RECEIPT)
            ? serialMapper.findInStockBySource(sourceType, sourceId, sourceLineId)
            : List.of();
    for (InventorySerial serial : serials) {
      if (serialMapper.updateStatus(
              serial.getId(), InventorySerialStatus.RETURNED, InventorySerialStatus.IN_STOCK)
          == 0) {
        throw new BizException(ErrorCode.INVENTORY_SERIAL_STATUS_INVALID);
      }
    }
    return serials;
  }

  /** 采购退货：按物料+序列号定位，必须存在且在库。 */
  @Transactional(readOnly = true)
  public List<InventorySerial> requireForReturn(long itemId, List<String> serialNos) {
    if (serialNos == null || serialNos.isEmpty()) {
      return List.of();
    }
    return serialNos.stream()
        .map(value -> normalize(value))
        .map(
            serialNo ->
                serialMapper
                    .findByItemAndSerial(itemId, serialNo)
                    .filter(serial -> serial.getStatus() == InventorySerialStatus.IN_STOCK)
                    .orElseThrow(() -> new BizException(ErrorCode.INVENTORY_SERIAL_DUPLICATE)))
        .toList();
  }

  @Transactional
  public void markReturned(long serialId) {
    if (serialMapper.updateStatus(
            serialId, InventorySerialStatus.RETURNED, InventorySerialStatus.IN_STOCK)
        == 0) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_STATUS_INVALID);
    }
  }

  @Transactional(readOnly = true)
  public PageResult<InventorySerialView> page(
      int page, int size, Long itemId, Long lotId, String status, String keyword) {
    PageQuery query = PageQuery.of(page, size);
    InventorySerialStatus parsed = parseStatus(status);
    String like = normalizeKeyword(keyword);
    List<InventorySerial> rows =
        serialMapper.findPage(itemId, lotId, parsed, like, query.offset(), query.size());
    return PageResult.of(
        views(rows), serialMapper.countBy(itemId, lotId, parsed, like), query.page(), query.size());
  }

  @Transactional(readOnly = true)
  public SerialTraceView trace(long id) {
    InventorySerial serial =
        serialMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.INVENTORY_SERIAL_DUPLICATE));
    return new SerialTraceView(
        views(List.of(serial)).get(0),
        movementSerialMapper.findMovements(id).stream()
            .map(
                row ->
                    new SerialMovementView(
                        row.movementId(),
                        row.movementNo(),
                        row.movementType(),
                        row.direction(),
                        row.sourceType(),
                        row.sourceId(),
                        row.occurredAt()))
            .toList());
  }

  private List<InventorySerialView> views(List<InventorySerial> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<Long, ItemBrief> items =
        masterDataQueryFacade.findItems(rows.stream().map(InventorySerial::getItemId).toList()).stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    Map<Long, SupplierBrief> suppliers =
        supplierQueryFacade
            .findByIds(rows.stream().map(InventorySerial::getSupplierId).filter(id -> id != null).toList())
            .stream()
            .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));
    Map<Long, InventoryLot> lots =
        lotViews(rows.stream().map(InventorySerial::getLotId).filter(id -> id != null && id > 0).toList());
    Map<Long, WarehouseBrief> warehouses =
        rows.stream()
            .map(InventorySerial::getWarehouseId)
            .distinct()
            .map(id -> masterDataQueryFacade.findWarehouse(id).orElse(null))
            .filter(item -> item != null)
            .collect(Collectors.toMap(WarehouseBrief::id, Function.identity()));
    Map<Long, LocationBrief> locations =
        masterDataQueryFacade
            .findLocations(
                rows.stream()
                    .map(InventorySerial::getLocationId)
                    .filter(id -> id != null && id > 0)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(LocationBrief::id, Function.identity()));
    return rows.stream()
        .map(
            row -> {
              ItemBrief item = items.get(row.getItemId());
              SupplierBrief supplier =
                  row.getSupplierId() == null ? null : suppliers.get(row.getSupplierId());
              InventoryLot lot = row.getLotId() == null ? null : lots.get(row.getLotId());
              WarehouseBrief warehouse = warehouses.get(row.getWarehouseId());
              LocationBrief location =
                  row.getLocationId() == null ? null : locations.get(row.getLocationId());
              return new InventorySerialView(
                  row.getId(),
                  row.getSerialNo(),
                  row.getItemId(),
                  item == null ? null : item.code(),
                  item == null ? null : item.name(),
                  row.getLotId() == null ? 0L : row.getLotId(),
                  lot == null ? null : lot.getLotNo(),
                  row.getSupplierId(),
                  supplier == null ? null : supplier.name(),
                  row.getWarehouseId(),
                  warehouse == null ? null : warehouse.name(),
                  row.getLocationId() == null ? 0L : row.getLocationId(),
                  location == null ? null : location.name(),
                  row.getStatus(),
                  row.getSourceType(),
                  row.getSourceId(),
                  row.getCreatedAt());
            })
        .toList();
  }

  private Map<Long, InventoryLot> lotViews(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return lotMapper.findByIds(ids).stream()
        .collect(Collectors.toMap(InventoryLot::getId, Function.identity()));
  }

  private static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_REQUIRED);
    }
    String trimmed = raw.trim();
    if (trimmed.length() > 64) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_REQUIRED, "序列号长度不得超过 64");
    }
    return trimmed;
  }

  private static String normalizeKeyword(String keyword) {
    if (keyword == null) {
      return null;
    }
    String trimmed = keyword.trim();
    return trimmed.isEmpty() ? null : SqlKeywords.escapeLike(trimmed);
  }

  private static InventorySerialStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return InventorySerialStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "序列号状态不合法：" + status);
    }
  }
}
