package com.spe.context.inventory.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.inventory.api.InventoryReceiveCommand;
import com.spe.context.inventory.api.InventoryReceiveFacade;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.WarehouseBrief;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 库存入库实现：按调用方给定成本入账，来源唯一键幂等。 */
@Service
@RequiredArgsConstructor
public class InventoryReceiveFacadeImpl implements InventoryReceiveFacade {

  private final InventoryMovementMapper movementMapper;
  private final InventoryStockMapper stockMapper;
  private final InventoryLotService lotService;
  private final InventoryValuationService valuationService;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuditFacade auditFacade;

  @Override
  @Transactional
  public void receive(List<InventoryReceiveCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      return;
    }
    Long firstMovementId = null;
    int count = 0;
    BigDecimal totalQty = BigDecimal.ZERO;
    for (InventoryReceiveCommand command : commands) {
      if (command.qty() == null || command.qty().signum() <= 0) {
        throw new BizException(ErrorCode.SALES_SHIPMENT_LINE_INVALID, "入库数量必须大于 0");
      }
      if (movementMapper
          .findBySource(
              command.sourceType(),
              command.sourceId(),
              command.sourceLineId(),
              InventoryMovementType.RECEIPT)
          .isPresent()) {
        continue;
      }
      WarehouseBrief warehouse =
          masterDataQueryFacade
              .findWarehouse(command.warehouseId())
              .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_NOT_FOUND));
      if (masterDataQueryFacade
              .findPlant(warehouse.plantId())
              .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND))
              .companyId()
          != command.companyId()) {
        throw new BizException(ErrorCode.WAREHOUSE_NOT_FOUND, "仓库不属于指定公司");
      }
      Long lotId = lotService.requireForMovement(command.itemId(), command.lotNo());
      long resolvedLot = lotId == null ? 0L : lotId;
      long locationId = Math.max(0, command.locationId());
      BigDecimal unitCost =
          command.unitCost() == null ? BigDecimal.ZERO : command.unitCost();
      BigDecimal amount =
          command.qty().multiply(unitCost).setScale(4, RoundingMode.HALF_UP);
      InventoryMovement movement = new InventoryMovement();
      movement.setMovementNo(InventorySupport.movementNo());
      movement.setCompanyId(command.companyId());
      movement.setPlantId(warehouse.plantId());
      movement.setWarehouseId(command.warehouseId());
      movement.setItemId(command.itemId());
      movement.setLotId(resolvedLot);
      movement.setLocationId(locationId);
      movement.setMovementType(InventoryMovementType.RECEIPT);
      movement.setDirection(MovementDirection.IN);
      movement.setQty(command.qty());
      movement.setUom(
          masterDataQueryFacade
              .findItem(command.itemId())
              .map(com.spe.context.masterdata.api.ItemBrief::baseUom)
              .orElse("EA"));
      movement.setUnitCost(unitCost);
      movement.setAmount(amount);
      movement.setCurrency(command.currency());
      movement.setSourceType(command.sourceType());
      movement.setSourceId(command.sourceId());
      movement.setSourceLineId(command.sourceLineId());
      movement.setOccurredAt(LocalDateTime.now());
      movement.setCreatedBy(InventorySupport.currentOperatorId());
      movementMapper.insert(movement);
      stockMapper.upsertReceipt(
          command.companyId(),
          warehouse.plantId(),
          command.warehouseId(),
          command.itemId(),
          resolvedLot,
          locationId,
          command.qty(),
          amount);
      valuationService.recordInbound(movement);
      if (firstMovementId == null) {
        firstMovementId = movement.getId();
      }
      count++;
      totalQty = totalQty.add(command.qty());
    }
    if (count > 0) {
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("sourceType", commands.get(0).sourceType());
      detail.put("sourceId", commands.get(0).sourceId());
      detail.put("movementType", InventoryMovementType.RECEIPT);
      detail.put("lineCount", count);
      detail.put("totalQty", totalQty);
      auditFacade.record(
          AuditAction.INVENTORY_MOVEMENT_POST,
          AuditTargetType.INVENTORY_MOVEMENT,
          firstMovementId,
          detail);
    }
  }
}
