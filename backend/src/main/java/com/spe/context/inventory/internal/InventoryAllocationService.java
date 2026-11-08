package com.spe.context.inventory.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 预留与冻结（docs/erp/54 §3.2/§4.5）：只调整可用量，不生成库存移动。 */
@Service
@RequiredArgsConstructor
public class InventoryAllocationService {

  private final InventoryReservationMapper reservationMapper;
  private final InventoryFreezeMapper freezeMapper;
  private final InventoryStockMapper stockMapper;
  private final InventorySerialMapper serialMapper;
  private final AuditFacade auditFacade;

  @Transactional
  public InventoryReservation reserve(
      long companyId,
      long warehouseId,
      Long locationId,
      long itemId,
      Long lotId,
      Long serialId,
      BigDecimal qty,
      String sourceType,
      long sourceId,
      Long sourceLineId,
      LocalDateTime expiresAt) {
    BigDecimal normalizedQty = requirePositive(qty, "预留数量必须大于 0");
    long resolvedLocation = locationId == null ? 0L : locationId;
    long resolvedLot = lotId == null ? 0L : lotId;
    long resolvedSerial = serialId == null ? 0L : serialId;
    if (resolvedSerial > 0) {
      InventorySerial serial =
          serialMapper
              .findById(resolvedSerial)
              .orElseThrow(() -> new BizException(ErrorCode.INVENTORY_SERIAL_DUPLICATE));
      if (serial.getStatus() != InventorySerialStatus.IN_STOCK) {
        throw new BizException(ErrorCode.INVENTORY_SERIAL_STATUS_INVALID);
      }
      if (normalizedQty.compareTo(BigDecimal.ONE) != 0) {
        throw new BizException(ErrorCode.BAD_REQUEST, "序列预留数量必须为 1");
      }
    }
    if (stockMapper.reserve(
            companyId, warehouseId, resolvedLocation, itemId, resolvedLot, normalizedQty)
        == 0) {
      throw new BizException(ErrorCode.INVENTORY_RESERVATION_INSUFFICIENT);
    }
    InventoryReservation reservation = new InventoryReservation();
    reservation.setCompanyId(companyId);
    reservation.setWarehouseId(warehouseId);
    reservation.setLocationId(resolvedLocation);
    reservation.setItemId(itemId);
    reservation.setLotId(resolvedLot);
    reservation.setSerialId(resolvedSerial);
    reservation.setQty(normalizedQty);
    reservation.setSourceType(sourceType.trim().toUpperCase());
    reservation.setSourceId(sourceId);
    reservation.setSourceLineId(sourceLineId == null ? 0L : sourceLineId);
    reservation.setStatus(InventoryReservationStatus.ACTIVE);
    reservation.setExpiresAt(expiresAt);
    reservation.setCreatedBy(InventorySupport.currentOperatorId());
    reservation.setVersion(0);
    try {
      reservationMapper.insert(reservation);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "同一来源行已存在预留");
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("warehouseId", warehouseId);
    detail.put("itemId", itemId);
    detail.put("qty", normalizedQty);
    detail.put("sourceType", reservation.getSourceType());
    detail.put("sourceId", sourceId);
    auditFacade.record(
        AuditAction.INVENTORY_RESERVATION_CREATE,
        AuditTargetType.INVENTORY_RESERVATION,
        reservation.getId(),
        detail);
    return reservation;
  }

  @Transactional
  public InventoryReservation releaseReservation(long id) {
    InventoryReservation reservation = requireReservation(id);
    if (reservationMapper.close(id, InventoryReservationStatus.RELEASED) == 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "预留不是 ACTIVE 状态");
    }
    releaseStock(reservation);
    auditFacade.record(
        AuditAction.INVENTORY_RESERVATION_RELEASE,
        AuditTargetType.INVENTORY_RESERVATION,
        id,
        Map.of("qty", reservation.getQty()));
    return reservation;
  }

  @Transactional
  public InventoryReservation consumeReservation(long id) {
    InventoryReservation reservation = requireReservation(id);
    if (reservationMapper.close(id, InventoryReservationStatus.CONSUMED) == 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "预留不是 ACTIVE 状态");
    }
    releaseStock(reservation);
    auditFacade.record(
        AuditAction.INVENTORY_RESERVATION_CONSUME,
        AuditTargetType.INVENTORY_RESERVATION,
        id,
        Map.of("qty", reservation.getQty()));
    return reservation;
  }

  /** 到期释放；由调用方触发（E2 不引入调度器）。 */
  @Transactional
  public int expireReservations() {
    int expired = 0;
    for (InventoryReservation reservation : reservationMapper.findExpired(LocalDateTime.now())) {
      if (reservationMapper.close(reservation.getId(), InventoryReservationStatus.EXPIRED) > 0) {
        releaseStock(reservation);
        expired++;
      }
    }
    return expired;
  }

  @Transactional
  public InventoryFreeze freeze(
      long companyId,
      long warehouseId,
      Long locationId,
      long itemId,
      Long lotId,
      Long serialId,
      BigDecimal qty,
      String reason,
      String sourceType,
      long sourceId) {
    BigDecimal normalizedQty = requirePositive(qty, "冻结数量必须大于 0");
    if (reason == null || reason.isBlank()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "冻结原因不能为空");
    }
    long resolvedLocation = locationId == null ? 0L : locationId;
    long resolvedLot = lotId == null ? 0L : lotId;
    long resolvedSerial = serialId == null ? 0L : serialId;
    if (stockMapper.freeze(
            companyId, warehouseId, resolvedLocation, itemId, resolvedLot, normalizedQty)
        == 0) {
      throw new BizException(ErrorCode.INVENTORY_FREEZE_INSUFFICIENT);
    }
    InventoryFreeze freeze = new InventoryFreeze();
    freeze.setCompanyId(companyId);
    freeze.setWarehouseId(warehouseId);
    freeze.setLocationId(resolvedLocation);
    freeze.setItemId(itemId);
    freeze.setLotId(resolvedLot);
    freeze.setSerialId(resolvedSerial);
    freeze.setQty(normalizedQty);
    freeze.setReason(reason.trim());
    freeze.setSourceType(sourceType.trim().toUpperCase());
    freeze.setSourceId(sourceId);
    freeze.setStatus(InventoryFreezeStatus.ACTIVE);
    freeze.setCreatedBy(InventorySupport.currentOperatorId());
    freeze.setVersion(0);
    try {
      freezeMapper.insert(freeze);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT, "同一来源已存在冻结");
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("warehouseId", warehouseId);
    detail.put("itemId", itemId);
    detail.put("qty", normalizedQty);
    detail.put("reason", freeze.getReason());
    auditFacade.record(
        AuditAction.INVENTORY_FREEZE_CREATE,
        AuditTargetType.INVENTORY_FREEZE,
        freeze.getId(),
        detail);
    return freeze;
  }

  @Transactional
  public InventoryFreeze releaseFreeze(long id) {
    InventoryFreeze freeze =
        freezeMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "冻结记录不存在"));
    if (freezeMapper.release(id) == 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "冻结不是 ACTIVE 状态");
    }
    if (stockMapper.releaseFrozen(
            freeze.getCompanyId(),
            freeze.getWarehouseId(),
            freeze.getLocationId(),
            freeze.getItemId(),
            freeze.getLotId(),
            freeze.getQty())
        == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    auditFacade.record(
        AuditAction.INVENTORY_FREEZE_RELEASE,
        AuditTargetType.INVENTORY_FREEZE,
        id,
        Map.of("qty", freeze.getQty()));
    return freeze;
  }

  @Transactional(readOnly = true)
  public PageResult<InventoryReservation> pageReservations(
      int page, int size, Long warehouseId, Long itemId, String status) {
    PageQuery query = PageQuery.of(page, size);
    InventoryReservationStatus parsed = parseReservationStatus(status);
    return PageResult.of(
        reservationMapper.findPage(warehouseId, itemId, parsed, query.offset(), query.size()),
        reservationMapper.countBy(warehouseId, itemId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public PageResult<InventoryFreeze> pageFreezes(
      int page, int size, Long warehouseId, Long itemId, String status) {
    PageQuery query = PageQuery.of(page, size);
    InventoryFreezeStatus parsed = parseFreezeStatus(status);
    return PageResult.of(
        freezeMapper.findPage(warehouseId, itemId, parsed, query.offset(), query.size()),
        freezeMapper.countBy(warehouseId, itemId, parsed),
        query.page(),
        query.size());
  }

  private void releaseStock(InventoryReservation reservation) {
    if (stockMapper.releaseReserved(
            reservation.getCompanyId(),
            reservation.getWarehouseId(),
            reservation.getLocationId(),
            reservation.getItemId(),
            reservation.getLotId(),
            reservation.getQty())
        == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
  }

  private InventoryReservation requireReservation(long id) {
    return reservationMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "预留记录不存在"));
  }

  private static BigDecimal requirePositive(BigDecimal qty, String message) {
    if (qty == null || qty.signum() <= 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, message);
    }
    return qty.setScale(6, java.math.RoundingMode.HALF_UP);
  }

  private static InventoryReservationStatus parseReservationStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return InventoryReservationStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "预留状态不合法：" + status);
    }
  }

  private static InventoryFreezeStatus parseFreezeStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return InventoryFreezeStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "冻结状态不合法：" + status);
    }
  }
}
