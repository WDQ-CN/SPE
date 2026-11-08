package com.spe.context.inventory.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.sql.SqlKeywords;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 库存批次（V1013）：收货自动建档、批次查询/状态管理。 */
@Service
@RequiredArgsConstructor
public class InventoryLotService {

  private final InventoryLotMapper lotMapper;
  private final InventoryStockMapper stockMapper;
  private final LotExpiryAlertMapper alertMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final AuditFacade auditFacade;

  /** 收货建档：同物料同批次号复用已存在批次；非 ACTIVE 批次拒绝继续收货。 */
  @Transactional
  public Long ensureForReceipt(
      long itemId,
      String lotNo,
      Long supplierId,
      String sourceType,
      long sourceId,
      LocalDate productionDate,
      LocalDate expiryDate) {
    String normalized = normalizeLotNo(lotNo);
    if (normalized == null) {
      return null;
    }
    validateDates(productionDate, expiryDate);
    InventoryLot existing = lotMapper.findByItemAndLotNo(itemId, normalized).orElse(null);
    if (existing != null) {
      if (existing.getStatus() != InventoryLotStatus.ACTIVE) {
        throw new BizException(ErrorCode.INVENTORY_LOT_STATUS_INVALID, "批次不是 ACTIVE，不能继续收货");
      }
      return existing.getId();
    }
    InventoryLot lot = new InventoryLot();
    lot.setLotNo(normalized);
    lot.setItemId(itemId);
    lot.setSupplierId(supplierId);
    lot.setSourceType(sourceType);
    lot.setSourceId(sourceId);
    lot.setProductionDate(productionDate);
    lot.setExpiryDate(expiryDate);
    lot.setStatus(InventoryLotStatus.ACTIVE);
    lot.setVersion(0);
    try {
      lotMapper.insert(lot);
      return lot.getId();
    } catch (DuplicateKeyException ex) {
      InventoryLot concurrent = lotMapper.findByItemAndLotNo(itemId, normalized).orElse(null);
      if (concurrent == null) {
        throw ex;
      }
      if (concurrent.getStatus() != InventoryLotStatus.ACTIVE) {
        throw new BizException(ErrorCode.INVENTORY_LOT_STATUS_INVALID);
      }
      return concurrent.getId();
    }
  }

  /** 出库/冲销只解析已存在批次；不存在直接 2303，不能凭空造批次。 */
  @Transactional(readOnly = true)
  public Long requireForMovement(long itemId, String lotNo) {
    String normalized = normalizeLotNo(lotNo);
    if (normalized == null) {
      return null;
    }
    return lotMapper
        .findByItemAndLotNo(itemId, normalized)
        .orElseThrow(() -> new BizException(ErrorCode.INVENTORY_LOT_NOT_FOUND))
        .getId();
  }

  @Transactional(readOnly = true)
  public PageResult<InventoryLotView> page(
      int page, int size, Long itemId, String keyword, String status) {
    PageQuery query = PageQuery.of(page, size);
    String like = normalizeKeyword(keyword);
    InventoryLotStatus parsed = parseStatus(status);
    List<InventoryLot> rows = lotMapper.findPage(itemId, like, parsed, query.offset(), query.size());
    return PageResult.of(
        toViews(rows),
        lotMapper.countBy(itemId, like, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public List<InventoryLot> findByIds(Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return lotMapper.findByIds(ids);
  }

  @Transactional(readOnly = true)
  public List<FefoAllocationView> fefo(
      long companyId, long warehouseId, long itemId, BigDecimal requiredQty, Long locationId) {
    BigDecimal qty = requiredQty == null ? null : requiredQty.setScale(6, java.math.RoundingMode.HALF_UP);
    if (qty == null || qty.signum() <= 0) {
      throw new BizException(ErrorCode.FEFO_STOCK_INSUFFICIENT);
    }
    List<InventoryStockMapper.FefoStockRow> rows =
        stockMapper.findFefo(companyId, warehouseId, itemId, locationId);
    BigDecimal total = rows.stream().map(InventoryStockMapper.FefoStockRow::availableQty)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(qty) < 0) {
      throw new BizException(ErrorCode.FEFO_STOCK_INSUFFICIENT);
    }
    Map<Long, String> locationNames =
        masterDataQueryFacade.findLocations(
            rows.stream().map(InventoryStockMapper.FefoStockRow::locationId).filter(id -> id > 0).distinct().toList())
            .stream().collect(Collectors.toMap(com.spe.context.masterdata.api.LocationBrief::id,
                com.spe.context.masterdata.api.LocationBrief::name));
    BigDecimal remaining = qty;
    List<FefoAllocationView> allocations = new java.util.ArrayList<>();
    for (InventoryStockMapper.FefoStockRow row : rows) {
      if (remaining.signum() <= 0) {
        break;
      }
      BigDecimal suggested = remaining.min(row.availableQty());
      remaining = remaining.subtract(suggested);
      allocations.add(
          new FefoAllocationView(
              row.lotId(),
              row.lotNo(),
              row.expiryDate(),
              row.locationId(),
              locationNames.get(row.locationId()),
              row.availableQty(),
              suggested));
    }
    return allocations;
  }

  @Transactional(readOnly = true)
  public List<ExpiringLotView> expiring(int days, Long warehouseId) {
    if (days < 1 || days > 365) {
      throw new BizException(ErrorCode.BAD_REQUEST, "预警天数必须在 1~365 之间");
    }
    List<InventoryLotMapper.ExpiringLotRow> rows =
        lotMapper.findExpiring(LocalDate.now().plusDays(days), warehouseId);
    Map<Long, ItemBrief> items =
        masterDataQueryFacade.findItems(rows.stream().map(InventoryLotMapper.ExpiringLotRow::itemId).toList())
            .stream().collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    LocalDate today = LocalDate.now();
    return rows.stream()
        .map(
            row -> {
              ItemBrief item = items.get(row.itemId());
              return new ExpiringLotView(
                  row.lotId(),
                  row.lotNo(),
                  row.itemId(),
                  item == null ? null : item.code(),
                  item == null ? null : item.name(),
                  row.expiryDate(),
                  ChronoUnit.DAYS.between(today, row.expiryDate()),
                  row.qtyOnHand());
            })
        .toList();
  }

  /** 扫描并去重记录预警；通知适配器后续接入。 */
  @Transactional
  public int scanExpiry(int days) {
    if (days < 1 || days > 365) {
      throw new BizException(ErrorCode.BAD_REQUEST, "预警天数必须在 1~365 之间");
    }
    return (int) lotMapper.findExpiring(LocalDate.now().plusDays(days), null).stream()
        .filter(row -> alertMapper.insertIfAbsent(row.lotId(), days) > 0)
        .count();
  }

  @Transactional(readOnly = true)
  public InventoryLot load(long id) {
    return lotMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.INVENTORY_LOT_NOT_FOUND));
  }

  @Transactional
  public void changeStatus(long id, String status, int version) {
    InventoryLot current = load(id);
    InventoryLotStatus target = parseStatus(status);
    if (target == null) {
      throw new BizException(ErrorCode.BAD_REQUEST, "批次状态不能为空");
    }
    if (current.getStatus() == target) {
      throw new BizException(ErrorCode.INVENTORY_LOT_STATUS_INVALID);
    }
    if (lotMapper.updateStatus(id, target, current.getStatus(), version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.INVENTORY_LOT_STATUS_CHANGE,
        AuditTargetType.INVENTORY_LOT,
        id,
        AuditDetails.transition(current.getStatus(), target));
  }

  private List<InventoryLotView> toViews(List<InventoryLot> rows) {
    Map<Long, ItemBrief> items =
        masterDataQueryFacade.findItems(rows.stream().map(InventoryLot::getItemId).toList()).stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    Map<Long, SupplierBrief> suppliers =
        supplierQueryFacade
            .findByIds(rows.stream().map(InventoryLot::getSupplierId).filter(Objects::nonNull).toList())
            .stream()
            .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));
    return rows.stream()
        .map(
            row -> {
              ItemBrief item = items.get(row.getItemId());
              SupplierBrief supplier =
                  row.getSupplierId() == null ? null : suppliers.get(row.getSupplierId());
              return new InventoryLotView(
                  row.getId(),
                  row.getLotNo(),
                  row.getItemId(),
                  item == null ? null : item.code(),
                  item == null ? null : item.name(),
                  row.getSupplierId(),
                  supplier == null ? null : supplier.name(),
                  row.getProductionDate(),
                  row.getExpiryDate(),
                  row.getStatus(),
                  row.getVersion(),
                  row.getCreatedAt());
            })
        .toList();
  }

  private static void validateDates(LocalDate productionDate, LocalDate expiryDate) {
    if (productionDate != null && expiryDate != null && expiryDate.isBefore(productionDate)) {
      throw new BizException(ErrorCode.INVENTORY_LOT_DATE_INVALID);
    }
  }

  private static String normalizeLotNo(String lotNo) {
    if (lotNo == null) {
      return null;
    }
    String trimmed = lotNo.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String normalizeKeyword(String keyword) {
    if (keyword == null) {
      return null;
    }
    String trimmed = keyword.trim();
    return trimmed.isEmpty() ? null : SqlKeywords.escapeLike(trimmed);
  }

  private static InventoryLotStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return InventoryLotStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "批次状态不合法：" + status);
    }
  }
}
