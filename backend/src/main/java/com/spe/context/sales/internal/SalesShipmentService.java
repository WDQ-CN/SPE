package com.spe.context.sales.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.inventory.api.InventoryIssueCommand;
import com.spe.context.inventory.api.InventoryIssueFacade;
import com.spe.context.inventory.api.InventoryIssueResult;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.PlantBrief;
import com.spe.context.masterdata.api.WarehouseBrief;
import com.spe.context.sales.api.events.SalesShipmentPostedEvent;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 销售发货（docs/erp/35 §4）：发货过账 → inventory.api 出库 → 订单行已发数量 + 订单状态。 */
@Service
@RequiredArgsConstructor
public class SalesShipmentService {

  private final SalesShipmentMapper shipmentMapper;
  private final SalesShipmentLineMapper shipmentLineMapper;
  private final SalesOrderMapper orderMapper;
  private final SalesOrderLineMapper orderLineMapper;
  private final InventoryIssueFacade inventoryIssueFacade;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  @Transactional
  public SalesShipmentView create(
      long operatorId, long orderId, long warehouseId, List<ShipmentLineCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.SALES_SHIPMENT_LINE_INVALID, "至少需要一行发货明细");
    }
    SalesOrder order =
        orderMapper
            .lockById(orderId)
            .orElseThrow(() -> new BizException(ErrorCode.SALES_ORDER_NOT_FOUND));
    if (order.getStatus() != SalesOrderStatus.CONFIRMED
        && order.getStatus() != SalesOrderStatus.PARTIAL_SHIPPED) {
      throw new BizException(ErrorCode.SALES_ORDER_STATUS_INVALID);
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
      throw new BizException(ErrorCode.WAREHOUSE_NOT_FOUND, "发货仓库不属于订单公司");
    }

    SalesShipment shipment = new SalesShipment();
    shipment.setShipmentNo(SalesSupport.docNo("SHP"));
    shipment.setCompanyId(order.getCompanyId());
    shipment.setCustomerId(order.getCustomerId());
    shipment.setOrderId(orderId);
    shipment.setWarehouseId(warehouseId);
    shipment.setCurrency(order.getCurrency());
    shipment.setStatus(SalesShipmentStatus.SHIPPED);
    shipment.setShippedAt(LocalDateTime.now(ZoneOffset.UTC));
    shipment.setCreatedBy(operatorId);
    shipment.setVersion(0);
    shipmentMapper.insert(shipment);

    List<InventoryIssueCommand> issueCommands = new ArrayList<>();
    List<SalesShipmentLine> shipmentLines = new ArrayList<>();
    int lineNo = 1;
    BigDecimal totalQty = BigDecimal.ZERO;
    for (ShipmentLineCommand command : commands) {
      SalesOrderLine orderLine =
          orderLineMapper
              .findByIdAndOrder(command.orderLineId(), orderId)
              .orElseThrow(() -> new BizException(ErrorCode.SALES_SHIPMENT_LINE_INVALID));
      if (command.qty() == null || command.qty().signum() <= 0) {
        throw new BizException(ErrorCode.SALES_SHIPMENT_LINE_INVALID, "发货数量必须大于 0");
      }
      BigDecimal available = orderLine.getQty().subtract(orderLine.getShippedQty());
      if (command.qty().compareTo(available) > 0) {
        throw new BizException(
            ErrorCode.SALES_SHIPMENT_LINE_INVALID,
            "发货数量超过未发数量：" + available);
      }
      ItemBrief item =
          masterDataQueryFacade
              .findItem(orderLine.getItemId())
              .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
      String lotNo = command.lotNo() == null ? null : command.lotNo().trim();
      if (item.lotControlled() && (lotNo == null || lotNo.isEmpty())) {
        throw new BizException(ErrorCode.INVENTORY_LOT_REQUIRED);
      }
      SalesShipmentLine line = new SalesShipmentLine();
      line.setShipmentId(shipment.getId());
      line.setLineNo(lineNo++);
      line.setOrderLineId(orderLine.getId());
      line.setItemId(orderLine.getItemId());
      line.setLotNo(lotNo);
      line.setLocationId(command.locationId() == null ? 0L : command.locationId());
      line.setQty(command.qty().setScale(6, java.math.RoundingMode.HALF_UP));
      shipmentLineMapper.insert(line);
      shipmentLines.add(line);
      issueCommands.add(
          new InventoryIssueCommand(
              order.getCompanyId(),
              warehouseId,
              line.getLocationId(),
              orderLine.getItemId(),
              lotNo,
              line.getQty(),
              order.getCurrency(),
              "SALES_SHIPMENT",
              shipment.getId(),
              line.getId()));
      totalQty = totalQty.add(line.getQty());
    }

    List<InventoryIssueResult> issueResults = inventoryIssueFacade.issue(issueCommands);
    Map<Long, InventoryIssueResult> costs =
        issueResults.stream()
            .collect(java.util.stream.Collectors.toMap(InventoryIssueResult::sourceLineId, r -> r));
    BigDecimal totalCost = BigDecimal.ZERO;
    for (SalesShipmentLine line : shipmentLines) {
      InventoryIssueResult result = costs.get(line.getId());
      BigDecimal unitCost = result == null ? BigDecimal.ZERO : result.unitCost();
      BigDecimal amount = result == null ? BigDecimal.ZERO : result.amount();
      shipmentLineMapper.updateCost(line.getId(), unitCost, amount);
      totalCost = totalCost.add(amount);
    }
    for (ShipmentLineCommand command : commands) {
      if (orderLineMapper.increaseShipped(
              command.orderLineId(), orderId, command.qty().setScale(6, java.math.RoundingMode.HALF_UP))
          == 0) {
        throw new BizException(ErrorCode.SALES_SHIPMENT_LINE_INVALID, "订单行已发数量校验失败");
      }
    }
    boolean allShipped =
        orderLineMapper.findByOrderId(orderId).stream()
            .allMatch(line -> line.getShippedQty().compareTo(line.getQty()) >= 0);
    orderMapper.markShippingStatus(
        orderId, allShipped ? SalesOrderStatus.SHIPPED : SalesOrderStatus.PARTIAL_SHIPPED);
    if (totalCost.signum() > 0) {
      eventPublisher.publish(
          new SalesShipmentPostedEvent(
              shipment.getId(),
              shipment.getCompanyId(),
              shipment.getCurrency(),
              totalCost,
              LocalDateTime.now(ZoneOffset.UTC).toLocalDate()));
    }

    auditFacade.record(
        AuditAction.SALES_SHIPMENT_POST,
        AuditTargetType.SALES_SHIPMENT,
        shipment.getId(),
        Map.of(
            "shipmentNo", shipment.getShipmentNo(),
            "orderId", orderId,
            "lineCount", commands.size(),
            "totalQty", totalQty,
            "totalCost", totalCost));
    return view(shipmentMapper.findById(shipment.getId()).orElseThrow());
  }

  @Transactional(readOnly = true)
  public PageResult<SalesShipment> page(
      int page, int size, Long companyId, Long orderId, String status) {
    PageQuery query = PageQuery.of(page, size);
    SalesShipmentStatus parsed =
        status == null || status.isBlank()
            ? null
            : parseStatus(status);
    return PageResult.of(
        shipmentMapper.findPage(companyId, orderId, parsed, query.offset(), query.size()),
        shipmentMapper.countBy(companyId, orderId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public SalesShipmentView detail(long id) {
    return view(
        shipmentMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.SALES_SHIPMENT_NOT_FOUND)));
  }

  private SalesShipmentView view(SalesShipment shipment) {
    return new SalesShipmentView(
        shipment.getId(),
        shipment.getShipmentNo(),
        shipment.getCompanyId(),
        shipment.getCustomerId(),
        shipment.getOrderId(),
        shipment.getWarehouseId(),
        shipment.getStatus(),
        shipment.getShippedAt(),
        shipment.getVersion(),
        shipmentLineMapper.findByShipmentId(shipment.getId()).stream()
            .map(
                line ->
                    new SalesShipmentLineView(
                        line.getId(),
                        line.getLineNo(),
                        line.getOrderLineId(),
                        line.getItemId(),
                        line.getLotNo(),
                        line.getLocationId(),
                        line.getQty()))
            .toList());
  }

  private static SalesShipmentStatus parseStatus(String status) {
    try {
      return SalesShipmentStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "发货状态不合法：" + status);
    }
  }

  /** 发货行命令。 */
  public record ShipmentLineCommand(
      long orderLineId, BigDecimal qty, String lotNo, Long locationId) {}
}
