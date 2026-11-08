package com.spe.context.inventory.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.PlantBrief;
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

/** 盘点（docs/erp/54 §3.4/§4.4）：快照 → 实盘 → 复核 → 过账。 */
@Service
@RequiredArgsConstructor
public class InventoryCountService {

  private static final String SOURCE = "INVENTORY_COUNT";

  private final InventoryCountMapper countMapper;
  private final InventoryCountLineMapper lineMapper;
  private final InventoryStockMapper stockMapper;
  private final InventoryMovementMapper movementMapper;
  private final LocationCapacityService capacityService;
  private final InventoryValuationService valuationService;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional
  public InventoryCountView create(
      long companyId,
      long warehouseId,
      Long itemId,
      Long lotId,
      Long locationId,
      String reason) {
    WarehouseBrief warehouse =
        masterDataQueryFacade
            .findWarehouse(warehouseId)
            .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_NOT_FOUND));
    PlantBrief plant =
        masterDataQueryFacade
            .findPlant(warehouse.plantId())
            .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
    if (plant.companyId() != companyId) {
      throw new BizException(ErrorCode.WAREHOUSE_NOT_FOUND, "仓库不属于指定公司");
    }
    List<InventoryStock> snapshot =
        stockMapper.findForSnapshot(companyId, warehouseId, itemId, lotId, locationId);
    if (snapshot.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "盘点范围内没有库存");
    }
    InventoryCount count = new InventoryCount();
    count.setCountNo(InventorySupport.documentNo("CNT"));
    count.setCompanyId(companyId);
    count.setWarehouseId(warehouseId);
    count.setStatus(InventoryCountStatus.DRAFT);
    count.setSnapshotAt(LocalDateTime.now());
    count.setReason(reason);
    count.setVersion(0);
    countMapper.insert(count);

    int lineNo = 1;
    for (InventoryStock stock : snapshot) {
      InventoryCountLine line = new InventoryCountLine();
      line.setCountId(count.getId());
      line.setLineNo(lineNo++);
      line.setItemId(stock.getItemId());
      line.setLotId(stock.getLotId());
      line.setLocationId(stock.getLocationId());
      line.setSnapshotQty(stock.getQtyOnHand());
      lineMapper.insert(line);
    }
    auditFacade.record(
        AuditAction.INVENTORY_COUNT_CREATE,
        AuditTargetType.INVENTORY_COUNT,
        count.getId(),
        detail(count, snapshot.size()));
    return detail(count.getId());
  }

  @Transactional
  public InventoryCountView recordCounts(long id, List<CountCommand> commands) {
    InventoryCount count = lock(id);
    if (count.getStatus() != InventoryCountStatus.DRAFT
        && count.getStatus() != InventoryCountStatus.COUNTING) {
      throw new BizException(ErrorCode.INVENTORY_COUNT_STATUS_INVALID);
    }
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "实盘数据不能为空");
    }
    Map<Long, InventoryCountLine> lines = new java.util.HashMap<>();
    for (InventoryCountLine line : lineMapper.findByCountId(id)) {
      lines.put(line.getId(), line);
    }
    for (CountCommand command : commands) {
      InventoryCountLine line = lines.get(command.lineId());
      if (line == null) {
        throw new BizException(ErrorCode.BAD_REQUEST, "盘点行不属于当前盘点单");
      }
      if (command.countedQty() == null || command.countedQty().signum() < 0) {
        throw new BizException(ErrorCode.BAD_REQUEST, "实盘数量不能为负");
      }
      BigDecimal counted = command.countedQty().setScale(6, RoundingMode.HALF_UP);
      BigDecimal variance = counted.subtract(line.getSnapshotQty());
      lineMapper.recordCount(line.getId(), id, counted, variance, command.reason());
    }
    return detail(id);
  }

  @Transactional
  public InventoryCountView submit(long id) {
    InventoryCount count = lock(id);
    if (count.getStatus() != InventoryCountStatus.DRAFT
        && count.getStatus() != InventoryCountStatus.COUNTING) {
      throw new BizException(ErrorCode.INVENTORY_COUNT_STATUS_INVALID);
    }
    List<InventoryCountLine> lines = lineMapper.findByCountId(id);
    if (lines.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "盘点单没有行");
    }
    if (lines.stream().anyMatch(line -> line.getCountedQty() == null)) {
      throw new BizException(ErrorCode.BAD_REQUEST, "所有盘点行都必须录入实盘数量");
    }
    if (countMapper.markSubmitted(id, InventorySupport.currentOperatorId()) == 0) {
      throw new BizException(ErrorCode.INVENTORY_COUNT_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.INVENTORY_COUNT_SUBMIT,
        AuditTargetType.INVENTORY_COUNT,
        id,
        detail(count, lines.size()));
    return detail(id);
  }

  @Transactional
  public InventoryCountView approve(long id) {
    InventoryCount count = lock(id);
    if (count.getStatus() != InventoryCountStatus.REVIEW) {
      throw new BizException(ErrorCode.INVENTORY_COUNT_STATUS_INVALID);
    }
    List<InventoryCountLine> lines = lineMapper.findByCountId(id);
    for (InventoryCountLine line : lines) {
      if (line.getVarianceQty() != null
          && line.getVarianceQty().signum() != 0
          && (line.getReason() == null || line.getReason().isBlank())) {
        throw new BizException(ErrorCode.BAD_REQUEST, "存在差异的盘点行必须填写原因");
      }
    }
    if (countMapper.markApproved(id, InventorySupport.currentOperatorId()) == 0) {
      throw new BizException(ErrorCode.INVENTORY_COUNT_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.INVENTORY_COUNT_APPROVE,
        AuditTargetType.INVENTORY_COUNT,
        id,
        detail(count, lines.size()));
    return detail(id);
  }

  @Transactional
  public InventoryCountView post(long id) {
    InventoryCount count = lock(id);
    if (count.getStatus() != InventoryCountStatus.APPROVED) {
      throw new BizException(ErrorCode.INVENTORY_COUNT_STATUS_INVALID);
    }
    List<InventoryCountLine> lines = lineMapper.findByCountId(id);
    long plantId =
        masterDataQueryFacade
            .findWarehouse(count.getWarehouseId())
            .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_NOT_FOUND))
            .plantId();
    int adjusted = 0;
    for (InventoryCountLine line : lines) {
      InventoryStock stock =
          stockMapper
              .lockOne(
                  count.getCompanyId(),
                  count.getWarehouseId(),
                  line.getItemId(),
                  line.getLotId(),
                  line.getLocationId())
              .orElse(null);
      // 快照后库存被并发改动时不能按旧差异过账，否则会把入库/出库覆盖掉；要求重新盘点。
      if (stock == null || stock.getQtyOnHand().compareTo(line.getSnapshotQty()) != 0) {
        throw new BizException(
            ErrorCode.CONCURRENT_CONFLICT, "盘点期间库存已变动，请重新盘点后再过账");
      }
      BigDecimal variance = line.getVarianceQty();
      if (variance == null || variance.signum() == 0) {
        continue;
      }
      BigDecimal unitCost =
          stock.getAvgUnitCost() == null ? BigDecimal.ZERO : stock.getAvgUnitCost();
      BigDecimal absQty = variance.abs();
      BigDecimal amount = absQty.multiply(unitCost).setScale(4, RoundingMode.HALF_UP);
      MovementDirection direction = variance.signum() > 0 ? MovementDirection.IN : MovementDirection.OUT;
      if (movementMapper
          .findBySource(SOURCE, id, line.getId(), InventoryMovementType.ADJUST)
          .isPresent()) {
        continue;
      }
      if (direction == MovementDirection.IN) {
        capacityService.checkInbound(line.getLocationId(), line.getItemId(), absQty);
      }
      InventoryMovement movement = new InventoryMovement();
      movement.setMovementNo(InventorySupport.movementNo());
      movement.setCompanyId(count.getCompanyId());
      movement.setPlantId(plantId);
      movement.setWarehouseId(count.getWarehouseId());
      movement.setItemId(line.getItemId());
      movement.setLotId(line.getLotId());
      movement.setLocationId(line.getLocationId());
      movement.setMovementType(InventoryMovementType.ADJUST);
      movement.setDirection(direction);
      movement.setQty(absQty);
      movement.setUom(
          masterDataQueryFacade
              .findItem(line.getItemId())
              .map(com.spe.context.masterdata.api.ItemBrief::baseUom)
              .orElse("EA"));
      movement.setUnitCost(unitCost);
      movement.setAmount(amount);
      movement.setCurrency(
          masterDataQueryFacade
              .findCompany(count.getCompanyId())
              .map(com.spe.context.masterdata.api.CompanyBrief::baseCurrency)
              .orElse("CNY"));
      movement.setSourceType(SOURCE);
      movement.setSourceId(id);
      movement.setSourceLineId(line.getId());
      movement.setOccurredAt(LocalDateTime.now());
      movement.setCreatedBy(InventorySupport.currentOperatorId());
      movementMapper.insert(movement);
      if (direction == MovementDirection.IN) {
        stockMapper.upsertReceipt(
            count.getCompanyId(),
            plantId,
            count.getWarehouseId(),
            line.getItemId(),
            line.getLotId(),
            line.getLocationId(),
            absQty,
            amount);
        valuationService.recordInbound(movement);
      } else if (stockMapper.decreaseForReversal(
              count.getCompanyId(),
              plantId,
              count.getWarehouseId(),
              line.getItemId(),
              line.getLotId(),
              line.getLocationId(),
              absQty,
              amount)
          == 0) {
        throw new BizException(ErrorCode.NEGATIVE_STOCK);
      } else {
        valuationService.recordOutbound(movement);
      }
      adjusted++;
    }
    if (countMapper.markPosted(id, InventorySupport.currentOperatorId()) == 0) {
      throw new BizException(ErrorCode.INVENTORY_COUNT_STATUS_INVALID);
    }
    Map<String, Object> detail = detail(count, lines.size());
    detail.put("adjustedLines", adjusted);
    auditFacade.record(
        AuditAction.INVENTORY_COUNT_POST, AuditTargetType.INVENTORY_COUNT, id, detail);
    return detail(id);
  }

  @Transactional
  public InventoryCountView cancel(long id) {
    InventoryCount count = lock(id);
    if (count.getStatus() == InventoryCountStatus.POSTED
        || count.getStatus() == InventoryCountStatus.CANCELED) {
      throw new BizException(ErrorCode.INVENTORY_COUNT_STATUS_INVALID);
    }
    if (countMapper.markCanceled(id) == 0) {
      throw new BizException(ErrorCode.INVENTORY_COUNT_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.INVENTORY_COUNT_CANCEL,
        AuditTargetType.INVENTORY_COUNT,
        id,
        detail(count, lineMapper.findByCountId(id).size()));
    return detail(id);
  }

  @Transactional(readOnly = true)
  public PageResult<InventoryCount> page(
      int page, int size, Long companyId, Long warehouseId, String status) {
    PageQuery query = PageQuery.of(page, size);
    InventoryCountStatus parsed = parseStatus(status);
    return PageResult.of(
        countMapper.findPage(companyId, warehouseId, parsed, query.offset(), query.size()),
        countMapper.countBy(companyId, warehouseId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public InventoryCountView detail(long id) {
    InventoryCount count =
        countMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "盘点单不存在"));
    return new InventoryCountView(
        count.getId(),
        count.getCountNo(),
        count.getCompanyId(),
        count.getWarehouseId(),
        count.getStatus(),
        count.getSnapshotAt(),
        count.getReason(),
        count.getVersion(),
        count.getCreatedAt(),
        lineMapper.findByCountId(id).stream()
            .map(
                line ->
                    new InventoryCountLineView(
                        line.getId(),
                        line.getLineNo(),
                        line.getItemId(),
                        line.getLotId(),
                        line.getLocationId(),
                        line.getSnapshotQty(),
                        line.getCountedQty(),
                        line.getVarianceQty(),
                        line.getReason()))
            .toList());
  }

  private InventoryCount lock(long id) {
    return countMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "盘点单不存在"));
  }

  private static Map<String, Object> detail(InventoryCount count, int lineCount) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("countNo", count.getCountNo());
    detail.put("warehouseId", count.getWarehouseId());
    detail.put("lineCount", lineCount);
    return detail;
  }

  private static InventoryCountStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return InventoryCountStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "盘点状态不合法：" + status);
    }
  }

  /** 实盘录入命令。 */
  public record CountCommand(long lineId, BigDecimal countedQty, String reason) {}
}
