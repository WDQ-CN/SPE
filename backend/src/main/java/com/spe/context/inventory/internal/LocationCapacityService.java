package com.spe.context.inventory.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.LocationBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.MasterDataStatus;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 库位容量（docs/erp/54 §3.5/§4.6）：只做阻断，不做自动拆分。 */
@Service
@RequiredArgsConstructor
public class LocationCapacityService {

  private final LocationCapacityMapper capacityMapper;
  private final InventoryStockMapper stockMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional
  public LocationCapacityView upsert(
      long locationId, long itemId, BigDecimal maxQty, String uom, String status) {
    LocationBrief location =
        masterDataQueryFacade
            .findLocation(locationId)
            .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_LOCATION_NOT_FOUND));
    if (maxQty == null || maxQty.signum() <= 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "库位容量上限必须大于 0");
    }
    MasterDataStatus parsedStatus = parseStatus(status);
    LocationCapacity existing = capacityMapper.find(locationId, itemId).orElse(null);
    if (existing == null) {
      LocationCapacity capacity = new LocationCapacity();
      capacity.setLocationId(locationId);
      capacity.setItemId(itemId);
      capacity.setMaxQty(maxQty);
      capacity.setUom(uom);
      capacity.setStatus(parsedStatus);
      capacity.setVersion(0);
      capacityMapper.insert(capacity);
      auditFacade.record(
          AuditAction.INVENTORY_LOCATION_CAPACITY_UPSERT,
          AuditTargetType.LOCATION_CAPACITY,
          capacity.getId(),
          detail(locationId, itemId, maxQty));
      return view(capacity, location);
    }
    if (capacityMapper.update(existing.getId(), maxQty, uom, parsedStatus, existing.getVersion()) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.INVENTORY_LOCATION_CAPACITY_UPSERT,
        AuditTargetType.LOCATION_CAPACITY,
        existing.getId(),
        detail(locationId, itemId, maxQty));
    existing.setMaxQty(maxQty);
    existing.setUom(uom);
    existing.setStatus(parsedStatus);
    existing.setVersion(existing.getVersion() + 1);
    return view(existing, location);
  }

  @Transactional(readOnly = true)
  public List<LocationCapacityView> list(long locationId) {
    LocationBrief location =
        masterDataQueryFacade
            .findLocation(locationId)
            .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_LOCATION_NOT_FOUND));
    return capacityMapper.findByLocation(locationId).stream()
        .map(capacity -> view(capacity, location))
        .toList();
  }

  /** 入库/调拨接收前校验；锁容量行串行化同库位并发。 */
  @Transactional
  public void checkInbound(long locationId, long itemId, BigDecimal incomingQty) {
    if (locationId <= 0 || incomingQty == null || incomingQty.signum() <= 0) {
      return;
    }
    List<LocationCapacity> capacities = capacityMapper.lockByLocation(locationId);
    if (capacities.isEmpty()) {
      return;
    }
    for (LocationCapacity capacity : capacities) {
      if (capacity.getStatus() != MasterDataStatus.ACTIVE) {
        continue;
      }
      BigDecimal current;
      if (capacity.getItemId() == 0) {
        current = stockMapper.sumAtLocation(locationId);
      } else if (capacity.getItemId() == itemId) {
        current = stockMapper.sumAtLocationByItem(locationId, itemId);
      } else {
        continue;
      }
      if (current.add(incomingQty).compareTo(capacity.getMaxQty()) > 0) {
        throw new BizException(
            ErrorCode.INVENTORY_LOCATION_CAPACITY_EXCEEDED, "库位 " + locationId + " 容量超限");
      }
    }
  }

  private LocationCapacityView view(LocationCapacity capacity, LocationBrief location) {
    ItemBrief item =
        capacity.getItemId() == 0
            ? null
            : masterDataQueryFacade.findItem(capacity.getItemId()).orElse(null);
    BigDecimal current =
        capacity.getItemId() == 0
            ? stockMapper.sumAtLocation(capacity.getLocationId())
            : stockMapper.sumAtLocationByItem(capacity.getLocationId(), capacity.getItemId());
    return new LocationCapacityView(
        capacity.getId(),
        capacity.getLocationId(),
        location == null ? null : location.code(),
        location == null ? null : location.name(),
        capacity.getItemId(),
        item == null ? null : item.code(),
        capacity.getMaxQty(),
        capacity.getUom(),
        current,
        capacity.getStatus(),
        capacity.getVersion());
  }

  private static Map<String, Object> detail(long locationId, long itemId, BigDecimal maxQty) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("locationId", locationId);
    detail.put("itemId", itemId);
    detail.put("maxQty", maxQty);
    return detail;
  }

  private static MasterDataStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return MasterDataStatus.ACTIVE;
    }
    try {
      return MasterDataStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "容量状态不合法：" + status);
    }
  }
}
