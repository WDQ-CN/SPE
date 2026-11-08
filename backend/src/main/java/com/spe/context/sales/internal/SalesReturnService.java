package com.spe.context.sales.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.inventory.api.InventoryReceiveCommand;
import com.spe.context.inventory.api.InventoryReceiveFacade;
import com.spe.context.masterdata.api.CustomerBrief;
import com.spe.context.masterdata.api.CustomerCreditFacade;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.sales.api.events.SalesReturnCreditedEvent;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

/** 销售退货/贷项（docs/erp/35 §4）：退货入库按原发货成本，贷项冲减 AR/收入/销项税。 */
@Service
@RequiredArgsConstructor
public class SalesReturnService {

  private final SalesReturnMapper returnMapper;
  private final SalesReturnLineMapper returnLineMapper;
  private final SalesShipmentMapper shipmentMapper;
  private final SalesShipmentLineMapper shipmentLineMapper;
  private final SalesOrderLineMapper orderLineMapper;
  private final InventoryReceiveFacade inventoryReceiveFacade;
  private final CustomerCreditFacade customerCreditFacade;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  @Transactional
  public SalesReturnView create(
      long operatorId,
      long shipmentId,
      String reason,
      List<ReturnLineCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.SALES_RETURN_STATUS_INVALID, "至少需要一行退货明细");
    }
    SalesShipment shipment =
        shipmentMapper
            .lockById(shipmentId)
            .orElseThrow(() -> new BizException(ErrorCode.SALES_SHIPMENT_NOT_FOUND));
    if (shipment.getStatus() != SalesShipmentStatus.SHIPPED
        && shipment.getStatus() != SalesShipmentStatus.DELIVERED) {
      throw new BizException(ErrorCode.SALES_SHIPMENT_NOT_FOUND, "发货单状态不可退货");
    }
    Map<Long, SalesShipmentLine> shipmentLines =
        shipmentLineMapper.findByShipmentId(shipmentId).stream()
            .collect(Collectors.toMap(SalesShipmentLine::getId, Function.identity()));
    SalesReturn entity = new SalesReturn();
    entity.setReturnNo(SalesSupport.docNo("SRT"));
    entity.setCompanyId(shipment.getCompanyId());
    entity.setCustomerId(shipment.getCustomerId());
    entity.setShipmentId(shipmentId);
    entity.setOrderId(shipment.getOrderId());
    entity.setCurrency(shipment.getCurrency());
    entity.setStatus(SalesReturnStatus.REQUESTED);
    entity.setReason(reason);
    entity.setVersion(0);
    entity.setCreatedBy(operatorId);
    returnMapper.insert(entity);

    BigDecimal netTotal = BigDecimal.ZERO;
    BigDecimal taxTotal = BigDecimal.ZERO;
    int lineNo = 1;
    for (ReturnLineCommand command : commands) {
      SalesShipmentLine shipmentLine = shipmentLines.get(command.shipmentLineId());
      if (shipmentLine == null) {
        throw new BizException(ErrorCode.SALES_RETURN_STATUS_INVALID, "发货行不属于该发货单");
      }
      if (command.qty() == null || command.qty().signum() <= 0) {
        throw new BizException(ErrorCode.SALES_RETURN_STATUS_INVALID, "退货数量必须大于 0");
      }
      BigDecimal available = shipmentLine.getQty().subtract(shipmentLine.getReturnedQty());
      if (command.qty().compareTo(available) > 0) {
        throw new BizException(ErrorCode.SALES_RETURN_STATUS_INVALID, "退货数量超过可退数量");
      }
      SalesOrderLine orderLine =
          orderLineMapper
              .findByIdAndOrder(shipmentLine.getOrderLineId(), shipment.getOrderId())
              .orElseThrow(() -> new BizException(ErrorCode.SALES_ORDER_LINE_INVALID));
      BigDecimal qty = command.qty().setScale(6, RoundingMode.HALF_UP);
      BigDecimal net =
          SalesSupport.money(
              qty.multiply(orderLine.getUnitPrice())
                  .multiply(BigDecimal.ONE.subtract(orderLine.getDiscountRate())));
      BigDecimal tax = SalesSupport.money(net.multiply(orderLine.getTaxRate()));
      SalesReturnLine line = new SalesReturnLine();
      line.setReturnId(entity.getId());
      line.setLineNo(lineNo++);
      line.setShipmentLineId(shipmentLine.getId());
      line.setOrderLineId(orderLine.getId());
      line.setItemId(shipmentLine.getItemId());
      line.setQty(qty);
      line.setUnitPrice(orderLine.getUnitPrice());
      line.setDiscountRate(orderLine.getDiscountRate());
      line.setTaxRate(orderLine.getTaxRate());
      line.setNetAmount(net);
      line.setTaxAmount(tax);
      line.setReason(command.reason());
      returnLineMapper.insert(line);
      netTotal = netTotal.add(net);
      taxTotal = taxTotal.add(tax);
    }
    entity.setNetAmount(SalesSupport.money(netTotal));
    entity.setTaxAmount(SalesSupport.money(taxTotal));
    entity.setTotalAmount(SalesSupport.money(netTotal.add(taxTotal)));
    returnMapper.updateById(entity);
    auditFacade.record(
        AuditAction.SALES_RETURN_CREATE,
        AuditTargetType.SALES_RETURN,
        entity.getId(),
        Map.of(
            "returnNo", entity.getReturnNo(),
            "shipmentId", shipmentId,
            "lineCount", commands.size(),
            "total", entity.getTotalAmount()));
    return detail(entity.getId());
  }

  @Transactional
  public SalesReturnView credit(long id, long operatorId) {
    SalesReturn entity =
        returnMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.SALES_RETURN_NOT_FOUND));
    if (entity.getStatus() != SalesReturnStatus.REQUESTED) {
      throw new BizException(ErrorCode.SALES_RETURN_STATUS_INVALID);
    }
    SalesShipment shipment =
        shipmentMapper
            .findById(entity.getShipmentId())
            .orElseThrow(() -> new BizException(ErrorCode.SALES_SHIPMENT_NOT_FOUND));
    List<SalesReturnLine> lines = returnLineMapper.findByReturnId(id);
    List<InventoryReceiveCommand> receiveCommands = new ArrayList<>();
    BigDecimal totalCost = BigDecimal.ZERO;
    for (SalesReturnLine line : lines) {
      SalesShipmentLine shipmentLine =
          shipmentLineMapper
              .findByIdAndShipment(line.getShipmentLineId(), entity.getShipmentId())
              .orElseThrow(() -> new BizException(ErrorCode.SALES_SHIPMENT_LINE_INVALID));
      BigDecimal unitCost =
          shipmentLine.getCostUnit() == null ? BigDecimal.ZERO : shipmentLine.getCostUnit();
      receiveCommands.add(
          new InventoryReceiveCommand(
              entity.getCompanyId(),
              shipment.getWarehouseId(),
              shipmentLine.getLocationId(),
              line.getItemId(),
              shipmentLine.getLotNo(),
              line.getQty(),
              unitCost,
              entity.getCurrency(),
              "SALES_RETURN",
              entity.getId(),
              line.getId()));
      totalCost = totalCost.add(line.getQty().multiply(unitCost));
    }
    inventoryReceiveFacade.receive(receiveCommands);
    for (SalesReturnLine line : lines) {
      if (shipmentLineMapper.increaseReturned(line.getShipmentLineId(), line.getQty()) == 0) {
        throw new BizException(ErrorCode.SALES_SHIPMENT_LINE_INVALID, "已退货数量校验失败");
      }
    }
    customerCreditFacade.decreaseUsed(entity.getCustomerId(), entity.getTotalAmount());
    eventPublisher.publish(
        new SalesReturnCreditedEvent(
            entity.getId(),
            entity.getCompanyId(),
            entity.getCustomerId(),
            entity.getCurrency(),
            entity.getNetAmount(),
            entity.getTaxAmount(),
            entity.getTotalAmount(),
            SalesSupport.money(totalCost),
            LocalDate.now(ZoneOffset.UTC)));
    returnMapper.markCredited(id, LocalDateTime.now(ZoneOffset.UTC));
    auditFacade.record(
        AuditAction.SALES_RETURN_CREDIT,
        AuditTargetType.SALES_RETURN,
        id,
        Map.of(
            "returnNo", entity.getReturnNo(),
            "total", entity.getTotalAmount(),
            "costTotal", totalCost));
    return detail(id);
  }

  @Transactional(readOnly = true)
  public PageResult<SalesReturn> page(
      int page, int size, Long companyId, Long customerId, String status) {
    PageQuery query = PageQuery.of(page, size);
    SalesReturnStatus parsed =
        status == null || status.isBlank()
            ? null
            : parseStatus(status);
    return PageResult.of(
        returnMapper.findPage(companyId, customerId, parsed, query.offset(), query.size()),
        returnMapper.countBy(companyId, customerId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public SalesReturnView detail(long id) {
    SalesReturn entity =
        returnMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.SALES_RETURN_NOT_FOUND));
    CustomerBrief customer =
        masterDataQueryFacade.findCustomer(entity.getCustomerId()).orElse(null);
    return new SalesReturnView(
        entity.getId(),
        entity.getReturnNo(),
        entity.getCompanyId(),
        entity.getCustomerId(),
        customer == null ? null : customer.name(),
        entity.getShipmentId(),
        entity.getOrderId(),
        entity.getCurrency(),
        entity.getStatus(),
        entity.getNetAmount(),
        entity.getTaxAmount(),
        entity.getTotalAmount(),
        entity.getReason(),
        entity.getCreditedAt(),
        entity.getVersion(),
        returnLineMapper.findByReturnId(id).stream()
            .map(
                line ->
                    new SalesReturnLineView(
                        line.getId(),
                        line.getLineNo(),
                        line.getShipmentLineId(),
                        line.getOrderLineId(),
                        line.getItemId(),
                        line.getQty(),
                        line.getUnitPrice(),
                        line.getDiscountRate(),
                        line.getTaxRate(),
                        line.getNetAmount(),
                        line.getTaxAmount(),
                        line.getReason()))
            .toList());
  }

  private static SalesReturnStatus parseStatus(String status) {
    try {
      return SalesReturnStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "退货状态不合法：" + status);
    }
  }

  /** 退货行命令。 */
  public record ReturnLineCommand(long shipmentLineId, BigDecimal qty, String reason) {}
}
