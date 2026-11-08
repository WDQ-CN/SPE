package com.spe.context.manufacturing.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.inventory.api.InventoryIssueCommand;
import com.spe.context.inventory.api.InventoryIssueFacade;
import com.spe.context.inventory.api.InventoryIssueResult;
import com.spe.context.inventory.api.InventoryReceiveCommand;
import com.spe.context.inventory.api.InventoryReceiveFacade;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工单：创建 → 下达（冻结 BOM）→ 领料 → 完工入库 → 关闭（docs/erp/36 §4）。 */
@Service
@RequiredArgsConstructor
public class WorkOrderService {

  private final WorkOrderMapper orderMapper;
  private final WorkOrderComponentMapper componentMapper;
  private final ProductionIssueMapper issueMapper;
  private final ProductionReceiptMapper receiptMapper;
  private final BomHeaderMapper bomHeaderMapper;
  private final BomLineMapper bomLineMapper;
  private final InventoryIssueFacade inventoryIssueFacade;
  private final InventoryReceiveFacade inventoryReceiveFacade;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional
  public WorkOrderView create(
      long operatorId, long companyId, long plantId, long itemId, long bomId, BigDecimal qty) {
    masterDataQueryFacade
        .findCompany(companyId)
        .filter(com.spe.context.masterdata.api.CompanyBrief::active)
        .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    PlantBrief plant =
        masterDataQueryFacade
            .findPlant(plantId)
            .filter(p -> p.active() && p.companyId() == companyId)
            .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
    ItemBrief item =
        masterDataQueryFacade
            .findItem(itemId)
            .filter(ItemBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
    BomHeader bom =
        bomHeaderMapper
            .findById(bomId)
            .filter(
                b ->
                    b.getStatus() == BomStatus.ACTIVE
                        && b.getItemId().equals(item.id())
                        && b.getCompanyId() == companyId)
            .orElseThrow(() -> new BizException(ErrorCode.BOM_STATUS_INVALID, "BOM 未生效或物料不匹配"));
    if (qty == null || qty.signum() <= 0) {
      throw new BizException(ErrorCode.WORK_ORDER_RECEIPT_EXCEEDS, "计划数量必须大于 0");
    }
    WorkOrder order = new WorkOrder();
    order.setOrderNo(ManufacturingSupport.docNo("WO"));
    order.setCompanyId(companyId);
    order.setPlantId(plant.id());
    order.setItemId(itemId);
    order.setBomId(bomId);
    order.setQty(ManufacturingSupport.qty(qty));
    order.setStatus(WorkOrderStatus.CREATED);
    order.setMaterialCost(BigDecimal.ZERO);
    order.setReceivedQty(BigDecimal.ZERO);
    order.setVersion(0);
    order.setCreatedBy(operatorId);
    orderMapper.insert(order);
    auditFacade.record(
        AuditAction.WORK_ORDER_CREATE,
        AuditTargetType.WORK_ORDER,
        order.getId(),
        Map.of("orderNo", order.getOrderNo(), "itemId", itemId, "qty", order.getQty()));
    return detail(order.getId());
  }

  @Transactional
  public WorkOrderView release(long id) {
    WorkOrder order = lock(id);
    if (order.getStatus() != WorkOrderStatus.CREATED) {
      throw new BizException(ErrorCode.WORK_ORDER_STATUS_INVALID);
    }
    List<BomLine> bomLines = bomLineMapper.findByBomId(order.getBomId());
    if (bomLines.isEmpty()) {
      throw new BizException(ErrorCode.WORK_ORDER_COMPONENT_INVALID, "BOM 没有组件");
    }
    int lineNo = 1;
    for (BomLine bomLine : bomLines) {
      WorkOrderComponent component = new WorkOrderComponent();
      component.setWorkOrderId(id);
      component.setLineNo(lineNo++);
      component.setBomLineId(bomLine.getId());
      component.setComponentItemId(bomLine.getComponentItemId());
      component.setQtyRequired(ManufacturingSupport.qty(bomLine.getQty().multiply(order.getQty())));
      component.setUom(bomLine.getUom());
      component.setIssuedQty(BigDecimal.ZERO);
      componentMapper.insert(component);
    }
    if (orderMapper.markReleased(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.WORK_ORDER_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.WORK_ORDER_RELEASE,
        AuditTargetType.WORK_ORDER,
        id,
        Map.of("orderNo", order.getOrderNo(), "componentCount", bomLines.size()));
    return detail(id);
  }

  @Transactional
  public WorkOrderView issue(long id, long operatorId, long warehouseId, List<IssueLineCommand> commands) {
    WorkOrder order = lock(id);
    if (order.getStatus() != WorkOrderStatus.RELEASED
        && order.getStatus() != WorkOrderStatus.IN_PROGRESS) {
      throw new BizException(ErrorCode.WORK_ORDER_STATUS_INVALID);
    }
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.WORK_ORDER_COMPONENT_INVALID, "至少需要一行领料");
    }
    WarehouseBrief warehouse =
        masterDataQueryFacade
            .findWarehouse(warehouseId)
            .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_NOT_FOUND));
    PlantBrief plant =
        masterDataQueryFacade
            .findPlant(warehouse.plantId())
            .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
    if (plant.companyId() != order.getCompanyId()) {
      throw new BizException(ErrorCode.WAREHOUSE_NOT_FOUND, "仓库不属于工单公司");
    }
    Map<Long, WorkOrderComponent> components =
        componentMapper.findByWorkOrderId(id).stream()
            .collect(Collectors.toMap(WorkOrderComponent::getId, Function.identity()));
    String currency =
        masterDataQueryFacade
            .findCompany(order.getCompanyId())
            .map(com.spe.context.masterdata.api.CompanyBrief::baseCurrency)
            .orElse("CNY");
    List<ProductionIssue> issues = new ArrayList<>();
    List<InventoryIssueCommand> inventoryCommands = new ArrayList<>();
    for (IssueLineCommand command : commands) {
      WorkOrderComponent component = components.get(command.componentId());
      if (component == null) {
        throw new BizException(ErrorCode.WORK_ORDER_COMPONENT_INVALID);
      }
      if (command.qty() == null || command.qty().signum() <= 0) {
        throw new BizException(ErrorCode.WORK_ORDER_ISSUE_EXCEEDS, "领料数量必须大于 0");
      }
      BigDecimal available = component.getQtyRequired().subtract(component.getIssuedQty());
      if (command.qty().compareTo(available) > 0) {
        throw new BizException(ErrorCode.WORK_ORDER_ISSUE_EXCEEDS);
      }
      ProductionIssue issue = new ProductionIssue();
      issue.setWorkOrderId(id);
      issue.setComponentId(component.getId());
      issue.setItemId(component.getComponentItemId());
      issue.setLotNo(command.lotNo());
      issue.setLocationId(command.locationId() == null ? 0L : command.locationId());
      issue.setQty(ManufacturingSupport.qty(command.qty()));
      issue.setUnitCost(BigDecimal.ZERO);
      issue.setAmount(BigDecimal.ZERO);
      issue.setCreatedBy(operatorId);
      issueMapper.insert(issue);
      issues.add(issue);
      inventoryCommands.add(
          new InventoryIssueCommand(
              order.getCompanyId(),
              warehouseId,
              issue.getLocationId(),
              issue.getItemId(),
              issue.getLotNo(),
              issue.getQty(),
              currency,
              "WORK_ORDER_ISSUE",
              id,
              issue.getId()));
    }
    List<InventoryIssueResult> results = inventoryIssueFacade.issue(inventoryCommands);
    Map<Long, InventoryIssueResult> costMap =
        results.stream().collect(Collectors.toMap(InventoryIssueResult::sourceLineId, r -> r));
    BigDecimal totalCost = BigDecimal.ZERO;
    for (ProductionIssue issue : issues) {
      InventoryIssueResult result = costMap.get(issue.getId());
      BigDecimal cost = result == null ? BigDecimal.ZERO : result.amount();
      issueMapper.updateCost(issue.getId(), result == null ? BigDecimal.ZERO : result.unitCost(), cost);
      if (componentMapper.increaseIssued(issue.getComponentId(), id, issue.getQty()) == 0) {
        throw new BizException(ErrorCode.WORK_ORDER_ISSUE_EXCEEDS, "工单组件已领数量校验失败");
      }
      totalCost = totalCost.add(cost);
    }
    if (totalCost.signum() > 0) {
      orderMapper.addMaterialCost(id, totalCost);
    }
    if (order.getStatus() == WorkOrderStatus.RELEASED) {
      orderMapper.markInProgress(id);
    }
    auditFacade.record(
        AuditAction.WORK_ORDER_ISSUE,
        AuditTargetType.WORK_ORDER,
        id,
        Map.of("orderNo", order.getOrderNo(), "lineCount", issues.size(), "cost", totalCost));
    return detail(id);
  }

  @Transactional
  public WorkOrderView receipt(
      long id, long operatorId, long warehouseId, Long locationId, String lotNo, BigDecimal qty) {
    WorkOrder order = lock(id);
    if (order.getStatus() != WorkOrderStatus.IN_PROGRESS) {
      throw new BizException(ErrorCode.WORK_ORDER_STATUS_INVALID);
    }
    if (qty == null || qty.signum() <= 0) {
      throw new BizException(ErrorCode.WORK_ORDER_RECEIPT_EXCEEDS, "完工数量必须大于 0");
    }
    BigDecimal remaining = order.getQty().subtract(order.getReceivedQty());
    if (qty.compareTo(remaining) > 0) {
      throw new BizException(ErrorCode.WORK_ORDER_RECEIPT_EXCEEDS);
    }
    WarehouseBrief warehouse =
        masterDataQueryFacade
            .findWarehouse(warehouseId)
            .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_NOT_FOUND));
    PlantBrief plant =
        masterDataQueryFacade
            .findPlant(warehouse.plantId())
            .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
    if (plant.companyId() != order.getCompanyId()) {
      throw new BizException(ErrorCode.WAREHOUSE_NOT_FOUND, "仓库不属于工单公司");
    }
    BigDecimal unitCost =
        order.getQty().signum() == 0
            ? BigDecimal.ZERO
            : order.getMaterialCost().divide(order.getQty(), 6, RoundingMode.HALF_UP);
    BigDecimal amount = ManufacturingSupport.money(qty.multiply(unitCost));
    ProductionReceipt receipt = new ProductionReceipt();
    receipt.setReceiptNo(ManufacturingSupport.docNo("PRD"));
    receipt.setWorkOrderId(id);
    receipt.setWarehouseId(warehouseId);
    receipt.setLocationId(locationId == null ? 0L : locationId);
    receipt.setLotNo(lotNo);
    receipt.setQty(ManufacturingSupport.qty(qty));
    receipt.setUnitCost(unitCost);
    receipt.setAmount(amount);
    receipt.setStatus(ProductionReceiptStatus.POSTED);
    receipt.setCreatedBy(operatorId);
    receiptMapper.insert(receipt);
    String currency =
        masterDataQueryFacade
            .findCompany(order.getCompanyId())
            .map(com.spe.context.masterdata.api.CompanyBrief::baseCurrency)
            .orElse("CNY");
    inventoryReceiveFacade.receive(
        List.of(
            new InventoryReceiveCommand(
                order.getCompanyId(),
                warehouseId,
                receipt.getLocationId(),
                order.getItemId(),
                lotNo,
                receipt.getQty(),
                unitCost,
                currency,
                "WORK_ORDER_RECEIPT",
                id,
                receipt.getId())));
    if (orderMapper.increaseReceived(id, receipt.getQty(), LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.WORK_ORDER_RECEIPT_EXCEEDS);
    }
    auditFacade.record(
        AuditAction.WORK_ORDER_RECEIPT,
        AuditTargetType.WORK_ORDER,
        id,
        Map.of("orderNo", order.getOrderNo(), "receiptNo", receipt.getReceiptNo(), "qty", qty));
    return detail(id);
  }

  @Transactional
  public WorkOrderView close(long id) {
    WorkOrder order = lock(id);
    if (orderMapper.markClosed(id) == 0) {
      throw new BizException(ErrorCode.WORK_ORDER_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.WORK_ORDER_CLOSE,
        AuditTargetType.WORK_ORDER,
        id,
        Map.of("orderNo", order.getOrderNo()));
    return detail(id);
  }

  @Transactional(readOnly = true)
  public PageResult<WorkOrder> page(int page, int size, Long companyId, String status) {
    PageQuery query = PageQuery.of(page, size);
    WorkOrderStatus parsed =
        status == null || status.isBlank() ? null : parseStatus(status);
    return PageResult.of(
        orderMapper.findPage(companyId, parsed, query.offset(), query.size()),
        orderMapper.countBy(companyId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public WorkOrderView detail(long id) {
    WorkOrder order =
        orderMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.WORK_ORDER_NOT_FOUND));
    ItemBrief item = masterDataQueryFacade.findItem(order.getItemId()).orElse(null);
    List<WorkOrderComponent> components = componentMapper.findByWorkOrderId(id);
    Map<Long, ItemBrief> componentItems =
        masterDataQueryFacade
            .findItems(components.stream().map(WorkOrderComponent::getComponentItemId).toList())
            .stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    return new WorkOrderView(
        order.getId(),
        order.getOrderNo(),
        order.getCompanyId(),
        order.getPlantId(),
        order.getItemId(),
        item == null ? null : item.code(),
        item == null ? null : item.name(),
        order.getBomId(),
        order.getQty(),
        order.getStatus(),
        order.getMaterialCost(),
        order.getReceivedQty(),
        order.getVersion(),
        order.getCreatedAt(),
        order.getReleasedAt(),
        order.getCompletedAt(),
        components.stream()
            .map(
                component -> {
                  ItemBrief componentItem = componentItems.get(component.getComponentItemId());
                  return new WorkOrderComponentView(
                      component.getId(),
                      component.getLineNo(),
                      component.getComponentItemId(),
                      componentItem == null ? null : componentItem.code(),
                      componentItem == null ? null : componentItem.name(),
                      component.getQtyRequired(),
                      component.getIssuedQty(),
                      component.getUom());
                })
            .toList(),
        issueMapper.findByWorkOrderId(id).stream()
            .map(
                issue ->
                    new ProductionIssueView(
                        issue.getId(),
                        issue.getComponentId(),
                        issue.getItemId(),
                        issue.getLotNo(),
                        issue.getLocationId(),
                        issue.getQty(),
                        issue.getUnitCost(),
                        issue.getAmount(),
                        issue.getCreatedAt()))
            .toList(),
        receiptMapper.findByWorkOrderId(id).stream()
            .map(
                receipt ->
                    new ProductionReceiptView(
                        receipt.getId(),
                        receipt.getReceiptNo(),
                        receipt.getWarehouseId(),
                        receipt.getLocationId(),
                        receipt.getLotNo(),
                        receipt.getQty(),
                        receipt.getUnitCost(),
                        receipt.getAmount(),
                        receipt.getStatus(),
                        receipt.getCreatedAt()))
            .toList());
  }

  private WorkOrder lock(long id) {
    return orderMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.WORK_ORDER_NOT_FOUND));
  }

  private static WorkOrderStatus parseStatus(String status) {
    try {
      return WorkOrderStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "工单状态不合法：" + status);
    }
  }

  /** 领料行命令。 */
  public record IssueLineCommand(long componentId, BigDecimal qty, String lotNo, Long locationId) {}
}
