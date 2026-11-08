package com.spe.context.inventory.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.WarehouseBrief;
import com.spe.context.procurement.api.events.GoodsReceiptReversedEvent;
import com.spe.context.procurement.api.events.GoodsReceivedEvent;
import com.spe.context.procurement.api.events.PurchaseReturnPostedEvent;
import com.spe.context.procurement.api.events.PurchaseReturnedLine;
import com.spe.context.procurement.api.events.PurchaseReturnedLine;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存台账（W3；docs/erp/33、34 §4）。
 *
 * <p>收货 → RECEIPT/IN；冲销 → REVERSAL/OUT。移动不可变，来源唯一键保证重复事件不重复记账；
 * 冲销用带非负守卫的 UPDATE，库存不足直接 2301 并回滚业务事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

  private static final String SOURCE_GOODS_RECEIPT = "GOODS_RECEIPT";
  private static final String SOURCE_PURCHASE_RETURN = "PURCHASE_RETURN";

  private final InventoryMovementMapper movementMapper;
  private final InventoryStockMapper stockMapper;
  private final InventoryLotService lotService;
  private final InventorySerialService serialService;
  private final InventoryMovementSerialMapper movementSerialMapper;
  private final InventoryValuationService valuationService;
  private final LocationCapacityService capacityService;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuditFacade auditFacade;
  private final InventoryPresenter presenter;

  @Transactional(readOnly = true)
  public PageResult<InventoryStockView> pageStock(
      int page,
      int size,
      Long companyId,
      Long plantId,
      Long warehouseId,
      Long itemId,
      Long lotId,
      Long locationId) {
    PageQuery query = PageQuery.of(page, size);
    List<InventoryStock> rows =
        stockMapper.findPage(
            companyId,
            plantId,
            warehouseId,
            itemId,
            lotId,
            locationId,
            query.offset(),
            query.size());
    return PageResult.of(
        presenter.stocks(rows),
        stockMapper.countBy(companyId, plantId, warehouseId, itemId, lotId, locationId),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public PageResult<InventoryMovementView> pageMovements(
      int page,
      int size,
      Long companyId,
      Long warehouseId,
      Long itemId,
      Long lotId,
      Long locationId,
      String sourceType,
      Long sourceId) {
    PageQuery query = PageQuery.of(page, size);
    String normalizedSource =
        sourceType == null || sourceType.isBlank()
            ? null
            : sourceType.trim().toUpperCase(Locale.ROOT);
    List<InventoryMovement> rows =
        movementMapper.findPage(
            companyId,
            warehouseId,
            itemId,
            lotId,
            locationId,
            normalizedSource,
            sourceId,
            query.offset(),
            query.size());
    return PageResult.of(
        presenter.movements(rows),
        movementMapper.countBy(
            companyId, warehouseId, itemId, lotId, locationId, normalizedSource, sourceId),
        query.page(),
        query.size());
  }

  @EventListener
  @Transactional
  public void onGoodsReceived(GoodsReceivedEvent event) {
    record(
        SOURCE_GOODS_RECEIPT,
        event.goodsReceiptId(),
        event.companyId(),
        event.supplierId(),
        event.warehouseId(),
        event.currency(),
        event.lines().stream()
            .map(
                line ->
                    new MovementLine(
                        line.goodsReceiptLineId(),
                        line.itemId(),
                        line.qty(),
                        line.uom(),
                        line.unitCost(),
                        line.amount(),
                        line.lotNo(),
                        line.locationId(),
                        line.productionDate(),
                        line.expiryDate(),
                        line.serials()))
            .toList(),
        InventoryMovementType.RECEIPT,
        MovementDirection.IN);
  }

  @EventListener
  @Transactional
  public void onGoodsReceiptReversed(GoodsReceiptReversedEvent event) {
    record(
        SOURCE_GOODS_RECEIPT,
        event.goodsReceiptId(),
        event.companyId(),
        null,
        event.warehouseId(),
        event.currency(),
        event.lines().stream()
            .map(
                line ->
                    new MovementLine(
                        line.goodsReceiptLineId(),
                        line.itemId(),
                        line.qty(),
                        line.uom(),
                        line.unitCost(),
                        line.amount(),
                        line.lotNo(),
                        line.locationId(),
                        null,
                        null,
                        java.util.List.of()))
            .toList(),
        InventoryMovementType.REVERSAL,
        MovementDirection.OUT);
  }

  @EventListener
  @Transactional
  public void onPurchaseReturnPosted(PurchaseReturnPostedEvent event) {
    record(
        SOURCE_PURCHASE_RETURN,
        event.purchaseReturnId(),
        event.companyId(),
        null,
        event.warehouseId(),
        event.currency(),
        event.lines().stream()
            .map(
                line ->
                    new MovementLine(
                        line.purchaseReturnLineId(),
                        line.itemId(),
                        line.qty(),
                        line.uom(),
                        line.unitCost(),
                        line.amount(),
                        line.lotNo(),
                        line.locationId(),
                        null,
                        null,
                        java.util.List.of()))
            .toList(),
        InventoryMovementType.RETURN,
        MovementDirection.OUT);
  }

  private void record(
      String sourceType,
      long sourceId,
      long companyId,
      Long supplierId,
      long warehouseId,
      String currency,
      List<MovementLine> lines,
      InventoryMovementType movementType,
      MovementDirection direction) {
    if (lines == null || lines.isEmpty()) {
      return;
    }
    WarehouseBrief warehouse =
        masterDataQueryFacade
            .findWarehouse(warehouseId)
            .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_NOT_FOUND));
    Long firstMovementId = null;
    int movementCount = 0;
    BigDecimal totalQty = BigDecimal.ZERO;
    for (MovementLine line : lines) {
      if (movementMapper
          .findBySource(sourceType, sourceId, line.sourceLineId(), movementType)
          .isPresent()) {
        continue;
      }
      InventoryMovement movement = new InventoryMovement();
      movement.setMovementNo(InventorySupport.movementNo());
      movement.setCompanyId(companyId);
      movement.setPlantId(warehouse.plantId());
      movement.setWarehouseId(warehouseId);
      movement.setItemId(line.itemId());
      long lotId =
          movementType == InventoryMovementType.RECEIPT
              ? java.util.Objects.requireNonNullElse(
                  lotService.ensureForReceipt(
                      line.itemId(),
                      line.lotNo(),
                      supplierId,
                      sourceType,
                      sourceId,
                      line.productionDate(),
                      line.expiryDate()),
                  0L)
              : java.util.Objects.requireNonNullElse(
                  lotService.requireForMovement(line.itemId(), line.lotNo()), 0L);
      movement.setLotId(lotId);
      movement.setLocationId(line.locationId() == null ? 0L : line.locationId());
      movement.setMovementType(movementType);
      movement.setDirection(direction);
      movement.setQty(line.qty());
      movement.setUom(line.uom());
      movement.setUnitCost(line.unitCost());
      movement.setAmount(line.amount());
      movement.setCurrency(currency);
      movement.setSourceType(sourceType);
      movement.setSourceId(sourceId);
      movement.setSourceLineId(line.sourceLineId());
      movement.setOccurredAt(LocalDateTime.now());
      movement.setCreatedBy(InventorySupport.currentOperatorId());
      try {
        movementMapper.insert(movement);
      } catch (DuplicateKeyException ex) {
        if (movementMapper
            .findBySource(sourceType, sourceId, line.sourceLineId(), movementType)
            .isPresent()) {
          continue;
        }
        throw ex;
      }
      if (movementType == InventoryMovementType.RECEIPT) {
        for (String serialNo : line.serials()) {
          long serialId =
              serialService.registerForReceipt(
                  line.itemId(),
                  lotId,
                  serialNo,
                  supplierId,
                  sourceType,
                  sourceId,
                  line.sourceLineId(),
                  warehouseId,
                  line.locationId() == null ? 0L : line.locationId());
          movementSerialMapper.link(movement.getId(), serialId, "IN");
        }
      } else if (movementType == InventoryMovementType.REVERSAL
          && SOURCE_GOODS_RECEIPT.equals(sourceType)) {
        for (InventorySerial serial :
            serialService.markReturnedBySource(sourceType, sourceId, line.sourceLineId())) {
          movementSerialMapper.link(movement.getId(), serial.getId(), "OUT");
        }
      } else if (movementType == InventoryMovementType.RETURN) {
        for (InventorySerial serial : serialService.requireForReturn(line.itemId(), line.serials())) {
          serialService.markReturned(serial.getId());
          movementSerialMapper.link(movement.getId(), serial.getId(), "OUT");
        }
      }
      if (direction == MovementDirection.IN) {
        long locationId = line.locationId() == null ? 0L : line.locationId();
        capacityService.checkInbound(locationId, line.itemId(), line.qty());
        stockMapper.upsertReceipt(
            companyId,
            warehouse.plantId(),
            warehouseId,
            line.itemId(),
            lotId,
            locationId,
            line.qty(),
            line.amount());
        valuationService.recordInbound(movement);
      } else if (stockMapper.decreaseForReversal(
              companyId,
              warehouse.plantId(),
              warehouseId,
              line.itemId(),
              lotId,
              line.locationId() == null ? 0L : line.locationId(),
              line.qty(),
              line.amount())
          == 0) {
        throw new BizException(ErrorCode.NEGATIVE_STOCK);
      }
      if (direction == MovementDirection.OUT) {
        valuationService.recordOutbound(movement);
      }
      if (firstMovementId == null) {
        firstMovementId = movement.getId();
      }
      movementCount++;
      totalQty = totalQty.add(line.qty());
    }
    if (movementCount == 0) {
      log.info("库存事件 {}#{} 已处理，跳过重复移动", sourceType, sourceId);
      return;
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("sourceType", sourceType);
    detail.put("sourceId", sourceId);
    detail.put("movementType", movementType);
    detail.put("lineCount", movementCount);
    detail.put("totalQty", totalQty);
    auditFacade.record(
        AuditAction.INVENTORY_MOVEMENT_POST,
        AuditTargetType.INVENTORY_MOVEMENT,
        firstMovementId,
        detail);
  }

  private record MovementLine(
      long sourceLineId,
      long itemId,
      BigDecimal qty,
      String uom,
      BigDecimal unitCost,
      BigDecimal amount,
      String lotNo,
      Long locationId,
      java.time.LocalDate productionDate,
      java.time.LocalDate expiryDate,
      java.util.List<String> serials) {}

}
