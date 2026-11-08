package com.spe.context.procurement.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.LocationBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.WarehouseBrief;
import com.spe.context.procurement.api.PurchaseOrderStatus;
import com.spe.context.procurement.api.events.PurchaseReturnPostedEvent;
import com.spe.context.procurement.api.events.PurchaseReturnedLine;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采购退货（W3 续；docs/erp/32 §5）。
 *
 * <p>退货以 PO 行为源：可退量 = 已收 - 已退。库存出库走 RETURN/OUT；若该 PO 已有批准发票，
 * 退货记为已开票（财务借记 AP），否则借记 GR/IR。发票匹配可开票量同步扣减已退数量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseReturnService {

  private final PurchaseReturnMapper returnMapper;
  private final PurchaseReturnLineMapper lineMapper;
  private final PurchaseOrderMapper purchaseOrderMapper;
  private final PurchaseOrderLineMapper purchaseOrderLineMapper;
  private final SupplierInvoiceMapper supplierInvoiceMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuditFacade auditFacade;
  private final EventPublisher eventPublisher;
  private final PurchaseReturnPresenter presenter;

  @Transactional(readOnly = true)
  public PageResult<PurchaseReturnSummaryView> page(
      int page, int size, Long companyId, Long poId, String keyword) {
    PageQuery query = PageQuery.of(page, size);
    String like = P2pSupport.keyword(keyword);
    List<PurchaseReturn> rows =
        returnMapper.findPage(companyId, poId, like, query.offset(), query.size());
    return PageResult.of(
        presenter.summaries(rows),
        returnMapper.countBy(companyId, poId, like),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public PurchaseReturnView detail(long id) {
    return presenter.view(load(id));
  }

  @Transactional
  public PurchaseReturnView create(
      long operatorId,
      long poId,
      long warehouseId,
      LocalDate returnDate,
      String reason,
      List<PurchaseReturnLineCommand> commands) {
    String normalizedReason = P2pSupport.required(reason, 200, "退货原因");
    PurchaseOrder order =
        purchaseOrderMapper
            .findByIdForUpdate(poId)
            .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
    if (order.getStatus() != PurchaseOrderStatus.PARTIAL_RECEIVED
        && order.getStatus() != PurchaseOrderStatus.RECEIVED) {
      throw new BizException(ErrorCode.PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED, "订单尚无收货，不能退货");
    }
    WarehouseBrief warehouse =
        masterDataQueryFacade
            .findWarehouse(warehouseId)
            .orElseThrow(() -> new BizException(ErrorCode.GOODS_RECEIPT_WAREHOUSE_INVALID));
    if (!warehouse.active() || warehouse.plantId() != order.getPlantId()) {
      throw new BizException(ErrorCode.GOODS_RECEIPT_WAREHOUSE_INVALID);
    }
    Normalized normalized = normalize(poId, warehouseId, commands);
    boolean invoiced = supplierInvoiceMapper.countApprovedByPo(poId) > 0;

    PurchaseReturn purchaseReturn = new PurchaseReturn();
    purchaseReturn.setDocNo(P2pSupport.docNo("PRT"));
    purchaseReturn.setCompanyId(order.getCompanyId());
    purchaseReturn.setSupplierId(order.getSupplierId());
    purchaseReturn.setPoId(poId);
    purchaseReturn.setWarehouseId(warehouseId);
    purchaseReturn.setReturnDate(returnDate == null ? LocalDate.now() : returnDate);
    purchaseReturn.setCurrency(order.getCurrency());
    purchaseReturn.setNetAmount(normalized.total());
    purchaseReturn.setInvoiced(invoiced);
    purchaseReturn.setStatus("POSTED");
    purchaseReturn.setReason(normalizedReason);
    purchaseReturn.setCreatedBy(operatorId);
    purchaseReturn.setVersion(0);
    returnMapper.insert(purchaseReturn);

    List<PurchaseReturnedLine> eventLines = new ArrayList<>();
    for (PurchaseReturnLine line : normalized.lines()) {
      line.setReturnId(purchaseReturn.getId());
      lineMapper.insert(line);
      if (purchaseOrderLineMapper.increaseReturned(
              line.getPoLineId(), poId, line.getQty())
          == 0) {
        throw new BizException(ErrorCode.PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED);
      }
      eventLines.add(
          new PurchaseReturnedLine(
              line.getId(),
              line.getPoLineId(),
              line.getItemId(),
              line.getQty(),
              line.getUom(),
              line.getUnitCost(),
              line.getAmount(),
              line.getLotNo(),
              line.getLocationId(),
              line.getSerials()));
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("poId", poId);
    detail.put("warehouseId", warehouseId);
    detail.put("lineCount", normalized.lines().size());
    detail.put("netAmount", normalized.total());
    detail.put("invoiced", invoiced);
    detail.put("reason", AuditDetails.commentSummary(normalizedReason));
    auditFacade.record(
        AuditAction.PURCHASE_RETURN_POST,
        AuditTargetType.PURCHASE_RETURN,
        purchaseReturn.getId(),
        detail);
    eventPublisher.publish(
        new PurchaseReturnPostedEvent(
            purchaseReturn.getId(),
            poId,
            order.getCompanyId(),
            warehouseId,
            order.getCurrency(),
            normalized.total(),
            invoiced,
            purchaseReturn.getReturnDate(),
            normalizedReason,
            eventLines));
    log.info(
        "采购退货 {}：PO {}，{} 行，金额 {}，已开票={}",
        purchaseReturn.getDocNo(),
        order.getDocNo(),
        normalized.lines().size(),
        normalized.total(),
        invoiced);
    return presenter.view(load(purchaseReturn.getId()));
  }

  public PurchaseReturn load(long id) {
    return returnMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_RETURN_NOT_FOUND));
  }

  private Normalized normalize(
      long poId, long warehouseId, List<PurchaseReturnLineCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED, "至少需要一行退货明细");
    }
    Set<Long> seen = new HashSet<>();
    List<PurchaseReturnLine> lines = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (PurchaseReturnLineCommand command : commands) {
      if (command == null || command.poLineId() <= 0) {
        throw new BizException(ErrorCode.PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED, "订单行不能为空");
      }
      if (!seen.add(command.poLineId())) {
        throw new BizException(ErrorCode.PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED, "同一订单行不能重复退货");
      }
      PurchaseOrderLine poLine =
          purchaseOrderLineMapper
              .findByIdAndPo(command.poLineId(), poId)
              .orElseThrow(
                  () -> new BizException(ErrorCode.PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED, "订单行不属于该订单"));
      BigDecimal qty = P2pSupport.qty(command.qty());
      if (qty == null || qty.signum() <= 0) {
        throw new BizException(ErrorCode.PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED, "退货数量必须大于 0");
      }
      ItemBrief item =
          masterDataQueryFacade
              .findItem(poLine.getItemId())
              .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
      List<String> serials = P2pSupport.serials(item.serialControlled(), qty, command.serials());
      String lotNo = P2pSupport.optional(command.lotNo(), 64, "批次号");
      if (item.lotControlled() && lotNo == null) {
        throw new BizException(ErrorCode.INVENTORY_LOT_REQUIRED, "物料 " + item.code() + " 必须指定批次");
      }
      long locationId = 0L;
      if (command.locationId() != null) {
        LocationBrief location =
            masterDataQueryFacade
                .findLocation(command.locationId())
                .orElseThrow(() -> new BizException(ErrorCode.WAREHOUSE_LOCATION_NOT_FOUND));
        if (!location.active() || location.warehouseId() != warehouseId) {
          throw new BizException(ErrorCode.WAREHOUSE_LOCATION_NOT_FOUND);
        }
        locationId = location.id();
      }
      BigDecimal returnable = poLine.getReceivedQty().subtract(poLine.getReturnedQty());
      if (qty.compareTo(returnable) > 0) {
        throw new BizException(
            ErrorCode.PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED,
            "可退数量为 " + returnable.max(BigDecimal.ZERO));
      }
      BigDecimal unitCost = P2pSupport.price(poLine.getPrice());
      BigDecimal amount = P2pSupport.lineAmount(qty, unitCost);
      P2pSupport.ensureAmount(amount, ErrorCode.PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED, "退货金额");
      PurchaseReturnLine line = new PurchaseReturnLine();
      line.setLineNo(lines.size() + 1);
      line.setPoLineId(poLine.getId());
      line.setItemId(poLine.getItemId());
      line.setQty(qty);
      line.setUom(poLine.getUom());
      line.setUnitCost(unitCost);
      line.setAmount(amount);
      line.setLotNo(lotNo);
      line.setLocationId(locationId);
      line.setSerials(serials);
      total = P2pSupport.sum(total, amount);
      P2pSupport.ensureAmount(total, ErrorCode.PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED, "退货合计");
      lines.add(line);
    }
    return new Normalized(lines, total);
  }

  private record Normalized(List<PurchaseReturnLine> lines, BigDecimal total) {}
}
