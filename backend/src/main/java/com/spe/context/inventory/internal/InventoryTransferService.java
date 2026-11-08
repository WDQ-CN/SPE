package com.spe.context.inventory.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.PlantBrief;
import com.spe.context.masterdata.api.WarehouseBrief;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 调拨（docs/erp/54 §3.3/§4.3）：DRAFT → IN_TRANSIT → RECEIVED，可取消。 */
@Service
@RequiredArgsConstructor
public class InventoryTransferService {

  private static final String SOURCE = "INVENTORY_TRANSFER";

  private final InventoryTransferMapper transferMapper;
  private final InventoryTransferLineMapper lineMapper;
  private final InventoryTransferLineSerialMapper lineSerialMapper;
  private final InventoryMovementMapper movementMapper;
  private final InventoryMovementSerialMapper movementSerialMapper;
  private final InventoryStockMapper stockMapper;
  private final InventoryLotMapper lotMapper;
  private final InventorySerialMapper serialMapper;
  private final LocationCapacityService capacityService;
  private final InventoryValuationService valuationService;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional
  public InventoryTransferView create(
      long companyId,
      long fromWarehouseId,
      Long fromLocationId,
      long toWarehouseId,
      Long toLocationId,
      String reason,
      List<LineCommand> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "调拨至少需要一行");
    }
    long sourceLocation = fromLocationId == null ? 0L : fromLocationId;
    long targetLocation = toLocationId == null ? 0L : toLocationId;
    if (fromWarehouseId == toWarehouseId && sourceLocation == targetLocation) {
      throw new BizException(ErrorCode.BAD_REQUEST, "源库位与目标库位不能相同");
    }
    WarehouseBrief from =
        masterDataQueryFacade
            .findWarehouse(fromWarehouseId)
            .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_NOT_FOUND));
    WarehouseBrief to =
        masterDataQueryFacade
            .findWarehouse(toWarehouseId)
            .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_NOT_FOUND));
    PlantBrief fromPlant =
        masterDataQueryFacade
            .findPlant(from.plantId())
            .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
    PlantBrief toPlant =
        masterDataQueryFacade
            .findPlant(to.plantId())
            .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
    if (fromPlant.companyId() != toPlant.companyId()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "E2 仅支持同公司调拨");
    }
    if (fromPlant.companyId() != companyId) {
      throw new BizException(ErrorCode.BAD_REQUEST, "调拨仓库不属于指定公司");
    }

    InventoryTransfer transfer = new InventoryTransfer();
    transfer.setTransferNo(InventorySupport.documentNo("TRF"));
    transfer.setCompanyId(companyId);
    transfer.setFromPlantId(from.plantId());
    transfer.setFromWarehouseId(fromWarehouseId);
    transfer.setFromLocationId(sourceLocation);
    transfer.setToPlantId(to.plantId());
    transfer.setToWarehouseId(toWarehouseId);
    transfer.setToLocationId(targetLocation);
    transfer.setStatus(InventoryTransferStatus.DRAFT);
    transfer.setReason(reason);
    transfer.setRequestedBy(InventorySupport.currentOperatorId());
    transfer.setVersion(0);
    transferMapper.insert(transfer);

    int lineNo = 1;
    for (LineCommand command : lines) {
      InventoryTransferLine line = buildLine(transfer.getId(), lineNo++, command, fromWarehouseId, sourceLocation);
      lineMapper.insert(line);
      for (Long serialId : safeSerials(command.serialIds())) {
        lineSerialMapper.insert(line.getId(), serialId);
      }
    }
    auditFacade.record(
        AuditAction.INVENTORY_TRANSFER_CREATE,
        AuditTargetType.INVENTORY_TRANSFER,
        transfer.getId(),
        detail(transfer, lines.size()));
    return detail(transfer.getId());
  }

  @Transactional
  public InventoryTransferView ship(long id) {
    InventoryTransfer transfer = lock(id);
    if (transfer.getStatus() != InventoryTransferStatus.DRAFT) {
      throw new BizException(ErrorCode.INVENTORY_TRANSFER_STATUS_INVALID);
    }
    List<InventoryTransferLine> lines = lineMapper.findByTransferId(id);
    if (lines.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "调拨单没有行");
    }
    for (InventoryTransferLine line : lines) {
      List<Long> serialIds = lineSerialMapper.findSerialIds(line.getId());
      validateSerialsForShip(line, serialIds, transfer.getFromWarehouseId(), transfer.getFromLocationId());
      InventoryMovement movement =
          postMovement(
              transfer,
              line,
              InventoryMovementType.TRANSFER_OUT,
              MovementDirection.OUT,
              transfer.getFromPlantId(),
              transfer.getFromWarehouseId(),
              transfer.getFromLocationId());
      if (movement == null) {
        continue;
      }
      if (stockMapper.decreaseForReversal(
              transfer.getCompanyId(),
              transfer.getFromPlantId(),
              transfer.getFromWarehouseId(),
              line.getItemId(),
              line.getLotId(),
              transfer.getFromLocationId(),
              line.getQty(),
              line.getAmount())
          == 0) {
        throw new BizException(ErrorCode.NEGATIVE_STOCK);
      }
      valuationService.recordOutbound(movement);
      for (Long serialId : serialIds) {
        movementSerialMapper.link(movement.getId(), serialId, "OUT");
      }
    }
    if (transferMapper.markShipped(id, InventorySupport.currentOperatorId(), LocalDateTime.now()) == 0) {
      throw new BizException(ErrorCode.INVENTORY_TRANSFER_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.INVENTORY_TRANSFER_SHIP,
        AuditTargetType.INVENTORY_TRANSFER,
        id,
        detail(transfer, lines.size()));
    return detail(id);
  }

  @Transactional
  public InventoryTransferView receive(long id) {
    InventoryTransfer transfer = lock(id);
    if (transfer.getStatus() != InventoryTransferStatus.IN_TRANSIT) {
      throw new BizException(ErrorCode.INVENTORY_TRANSFER_STATUS_INVALID);
    }
    List<InventoryTransferLine> lines = lineMapper.findByTransferId(id);
    for (InventoryTransferLine line : lines) {
      capacityService.checkInbound(
          transfer.getToLocationId(), line.getItemId(), line.getQty());
      InventoryMovement movement =
          postMovement(
              transfer,
              line,
              InventoryMovementType.TRANSFER_IN,
              MovementDirection.IN,
              transfer.getToPlantId(),
              transfer.getToWarehouseId(),
              transfer.getToLocationId());
      if (movement == null) {
        continue;
      }
      stockMapper.upsertReceipt(
          transfer.getCompanyId(),
          transfer.getToPlantId(),
          transfer.getToWarehouseId(),
          line.getItemId(),
          line.getLotId(),
          transfer.getToLocationId(),
          line.getQty(),
          line.getAmount());
      valuationService.recordInbound(movement);
      for (Long serialId : lineSerialMapper.findSerialIds(line.getId())) {
        if (serialMapper.updateLocation(
                serialId, transfer.getToWarehouseId(), transfer.getToLocationId())
            == 0) {
          throw new BizException(ErrorCode.INVENTORY_SERIAL_STATUS_INVALID);
        }
        movementSerialMapper.link(movement.getId(), serialId, "IN");
      }
    }
    if (transferMapper.markReceived(id, LocalDateTime.now()) == 0) {
      throw new BizException(ErrorCode.INVENTORY_TRANSFER_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.INVENTORY_TRANSFER_RECEIVE,
        AuditTargetType.INVENTORY_TRANSFER,
        id,
        detail(transfer, lines.size()));
    return detail(id);
  }

  @Transactional
  public InventoryTransferView cancel(long id) {
    InventoryTransfer transfer = lock(id);
    if (transfer.getStatus() == InventoryTransferStatus.CANCELED
        || transfer.getStatus() == InventoryTransferStatus.RECEIVED) {
      throw new BizException(ErrorCode.INVENTORY_TRANSFER_STATUS_INVALID);
    }
    List<InventoryTransferLine> lines = lineMapper.findByTransferId(id);
    if (transfer.getStatus() == InventoryTransferStatus.IN_TRANSIT) {
      for (InventoryTransferLine line : lines) {
        InventoryMovement movement =
            postMovement(
                transfer,
                line,
                InventoryMovementType.TRANSFER_IN,
                MovementDirection.IN,
                transfer.getFromPlantId(),
                transfer.getFromWarehouseId(),
                transfer.getFromLocationId());
        if (movement == null) {
          continue;
        }
        stockMapper.upsertReceipt(
            transfer.getCompanyId(),
            transfer.getFromPlantId(),
            transfer.getFromWarehouseId(),
            line.getItemId(),
            line.getLotId(),
            transfer.getFromLocationId(),
            line.getQty(),
            line.getAmount());
        valuationService.recordInbound(movement);
        for (Long serialId : lineSerialMapper.findSerialIds(line.getId())) {
          serialMapper.updateLocation(
              serialId, transfer.getFromWarehouseId(), transfer.getFromLocationId());
          movementSerialMapper.link(movement.getId(), serialId, "IN");
        }
      }
    }
    if (transferMapper.markCanceled(id) == 0) {
      throw new BizException(ErrorCode.INVENTORY_TRANSFER_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.INVENTORY_TRANSFER_CANCEL,
        AuditTargetType.INVENTORY_TRANSFER,
        id,
        detail(transfer, lines.size()));
    return detail(id);
  }

  @Transactional(readOnly = true)
  public PageResult<InventoryTransfer> page(int page, int size, Long companyId, String status) {
    PageQuery query = PageQuery.of(page, size);
    InventoryTransferStatus parsed = parseStatus(status);
    return PageResult.of(
        transferMapper.findPage(companyId, parsed, query.offset(), query.size()),
        transferMapper.countBy(companyId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public InventoryTransferView detail(long id) {
    InventoryTransfer transfer =
        transferMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "调拨单不存在"));
    List<InventoryTransferLineView> lines =
        lineMapper.findByTransferId(id).stream()
            .map(
                line ->
                    new InventoryTransferLineView(
                        line.getId(),
                        line.getLineNo(),
                        line.getItemId(),
                        line.getLotId(),
                        line.getQty(),
                        line.getUom(),
                        line.getUnitCost(),
                        line.getAmount(),
                        lineSerialMapper.findSerialIds(line.getId())))
            .toList();
    return new InventoryTransferView(
        transfer.getId(),
        transfer.getTransferNo(),
        transfer.getCompanyId(),
        transfer.getFromPlantId(),
        transfer.getFromWarehouseId(),
        transfer.getFromLocationId(),
        transfer.getToPlantId(),
        transfer.getToWarehouseId(),
        transfer.getToLocationId(),
        transfer.getStatus(),
        transfer.getReason(),
        transfer.getShippedAt(),
        transfer.getReceivedAt(),
        transfer.getVersion(),
        transfer.getCreatedAt(),
        lines);
  }

  private InventoryTransferLine buildLine(
      long transferId, int lineNo, LineCommand command, long warehouseId, long locationId) {
    if (command.qty() == null || command.qty().signum() <= 0) {
      throw new BizException(ErrorCode.BAD_REQUEST, "调拨数量必须大于 0");
    }
    ItemBrief item =
        masterDataQueryFacade
            .findItem(command.itemId())
            .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
    long lotId = command.lotId() == null ? 0L : command.lotId();
    if (item.lotControlled() && lotId == 0) {
      throw new BizException(ErrorCode.INVENTORY_LOT_REQUIRED);
    }
    if (lotId > 0) {
      InventoryLot lot =
          lotMapper
              .findById(lotId)
              .orElseThrow(() -> new BizException(ErrorCode.INVENTORY_LOT_NOT_FOUND));
      if (!lot.getItemId().equals(command.itemId())) {
        throw new BizException(ErrorCode.INVENTORY_LOT_NOT_FOUND);
      }
    }
    List<Long> serialIds = safeSerials(command.serialIds());
    if (item.serialControlled()) {
      if (command.qty().stripTrailingZeros().scale() > 0
          || serialIds.size() != command.qty().intValueExact()) {
        throw new BizException(ErrorCode.INVENTORY_SERIAL_REQUIRED);
      }
      validateSerialSet(serialIds, item, lotId, warehouseId, locationId);
    } else if (!serialIds.isEmpty()) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_STATUS_INVALID);
    }
    BigDecimal unitCost =
        command.unitCost() == null
            ? BigDecimal.ZERO
            : command.unitCost().setScale(6, RoundingMode.HALF_UP);
    InventoryTransferLine line = new InventoryTransferLine();
    line.setTransferId(transferId);
    line.setLineNo(lineNo);
    line.setItemId(command.itemId());
    line.setLotId(lotId);
    line.setQty(command.qty().setScale(6, RoundingMode.HALF_UP));
    line.setUom(command.uom());
    line.setUnitCost(unitCost);
    line.setAmount(line.getQty().multiply(unitCost).setScale(4, RoundingMode.HALF_UP));
    return line;
  }

  private void validateSerialSet(
      List<Long> serialIds,
      ItemBrief item,
      long lotId,
      long warehouseId,
      long locationId) {
    Set<Long> unique = new HashSet<>(serialIds);
    if (unique.size() != serialIds.size()) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_REQUIRED, "序列号不能重复");
    }
    List<InventorySerial> serials = serialMapper.findByIds(serialIds);
    if (serials.size() != serialIds.size()) {
      throw new BizException(ErrorCode.INVENTORY_SERIAL_DUPLICATE);
    }
    for (InventorySerial serial : serials) {
      if (serial.getStatus() != InventorySerialStatus.IN_STOCK
          || serial.getItemId() != item.id()
          || serial.getWarehouseId() != warehouseId
          || serial.getLotId() != lotId
          || (locationId > 0 && serial.getLocationId() != locationId)) {
        throw new BizException(ErrorCode.INVENTORY_SERIAL_STATUS_INVALID);
      }
    }
  }

  private void validateSerialsForShip(
      InventoryTransferLine line, List<Long> serialIds, long warehouseId, long locationId) {
    if (serialIds.isEmpty()) {
      return;
    }
    for (InventorySerial serial : serialMapper.findByIds(serialIds)) {
      if (serial.getStatus() != InventorySerialStatus.IN_STOCK
          || serial.getWarehouseId() != warehouseId
          || serial.getLotId() != line.getLotId()
          || (locationId > 0 && serial.getLocationId() != locationId)) {
        throw new BizException(ErrorCode.INVENTORY_SERIAL_STATUS_INVALID);
      }
    }
  }

  private InventoryMovement postMovement(
      InventoryTransfer transfer,
      InventoryTransferLine line,
      InventoryMovementType type,
      MovementDirection direction,
      long plantId,
      long warehouseId,
      long locationId) {
    if (movementMapper
        .findBySource(SOURCE, transfer.getId(), line.getId(), type)
        .isPresent()) {
      return null;
    }
    InventoryMovement movement = new InventoryMovement();
    movement.setMovementNo(InventorySupport.movementNo());
    movement.setCompanyId(transfer.getCompanyId());
    movement.setPlantId(plantId);
    movement.setWarehouseId(warehouseId);
    movement.setItemId(line.getItemId());
    movement.setLotId(line.getLotId());
    movement.setLocationId(locationId);
    movement.setMovementType(type);
    movement.setDirection(direction);
    movement.setQty(line.getQty());
    movement.setUom(line.getUom());
    movement.setUnitCost(line.getUnitCost());
    movement.setAmount(line.getAmount());
    movement.setCurrency(currencyOf(transfer.getCompanyId()));
    movement.setSourceType(SOURCE);
    movement.setSourceId(transfer.getId());
    movement.setSourceLineId(line.getId());
    movement.setOccurredAt(LocalDateTime.now());
    movement.setCreatedBy(InventorySupport.currentOperatorId());
    try {
      movementMapper.insert(movement);
    } catch (DuplicateKeyException ex) {
      return null;
    }
    return movement;
  }

  private String currencyOf(long companyId) {
    return masterDataQueryFacade
        .findCompany(companyId)
        .map(com.spe.context.masterdata.api.CompanyBrief::baseCurrency)
        .orElse("CNY");
  }

  private InventoryTransfer lock(long id) {
    return transferMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "调拨单不存在"));
  }

  private static List<Long> safeSerials(List<Long> serialIds) {
    return serialIds == null ? List.of() : serialIds.stream().filter(v -> v != null && v > 0).toList();
  }

  private static Map<String, Object> detail(InventoryTransfer transfer, int lineCount) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("transferNo", transfer.getTransferNo());
    detail.put("fromWarehouseId", transfer.getFromWarehouseId());
    detail.put("toWarehouseId", transfer.getToWarehouseId());
    detail.put("lineCount", lineCount);
    return detail;
  }

  private static InventoryTransferStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return InventoryTransferStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "调拨状态不合法：" + status);
    }
  }

  /** 调拨创建行命令。 */
  public record LineCommand(
      long itemId,
      Long lotId,
      BigDecimal qty,
      String uom,
      BigDecimal unitCost,
      List<Long> serialIds) {}
}
