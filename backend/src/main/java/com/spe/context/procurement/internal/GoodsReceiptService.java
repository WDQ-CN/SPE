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
import com.spe.context.procurement.api.GoodsReceiptStatus;
import com.spe.context.procurement.api.PurchaseOrderStatus;
import com.spe.context.procurement.api.events.GoodsReceiptReversedEvent;
import com.spe.context.procurement.api.events.GoodsReceivedEvent;
import com.spe.context.procurement.api.events.GoodsReceivedLine;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
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
 * 收货（W2；docs/erp/32 §4）。
 *
 * <p>只允许对 SENT/PARTIAL_RECEIVED 的 PO 收货；仓库必须属于订单工厂。E1 不做超收容差：
 * `已收 + 本次 <= 订购` 由行级守卫 UPDATE 兜底（并发下第二个请求影响 0 行 → 2204）。库存台账与
 * 估值在 W3，本步只维护 PO 已收数量与 PO 状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsReceiptService {

  private final GoodsReceiptMapper goodsReceiptMapper;
  private final GoodsReceiptLineMapper lineMapper;
  private final PurchaseOrderMapper purchaseOrderMapper;
  private final PurchaseOrderLineMapper purchaseOrderLineMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuditFacade auditFacade;
  private final ProcurementPresenter presenter;
  private final EventPublisher eventPublisher;

  @Transactional(readOnly = true)
  public PageResult<GoodsReceiptSummaryView> page(
      int page, int size, Long companyId, Long poId, String keyword) {
    PageQuery query = PageQuery.of(page, size);
    String like = P2pSupport.keyword(keyword);
    List<GoodsReceipt> rows =
        goodsReceiptMapper.findPage(companyId, poId, like, query.offset(), query.size());
    return PageResult.of(
        presenter.goodsReceipts(rows),
        goodsReceiptMapper.countBy(companyId, poId, like),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public GoodsReceiptView detail(long id) {
    return presenter.goodsReceipt(load(id));
  }

  @Transactional
  public GoodsReceiptView create(
      long operatorId, long poId, long warehouseId, List<GoodsReceiptLineCommand> commands) {
    // 锁订单头：与取消/开票串行；锁定后再判状态，取消者无法在本次收货提交后把单改成 CANCELED。
    PurchaseOrder order =
        purchaseOrderMapper
            .findByIdForUpdate(poId)
            .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
    if (order.getStatus() != PurchaseOrderStatus.SENT
        && order.getStatus() != PurchaseOrderStatus.PARTIAL_RECEIVED) {
      throw new BizException(ErrorCode.GOODS_RECEIPT_PO_NOT_RECEIVABLE);
    }
    if (order.getCreatedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    if (order.getApprovedBy() != null && order.getApprovedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "PO 审批人不能收货");
    }
    WarehouseBrief warehouse =
        masterDataQueryFacade
            .findWarehouse(warehouseId)
            .orElseThrow(() -> new BizException(ErrorCode.GOODS_RECEIPT_WAREHOUSE_INVALID));
    if (!warehouse.active() || warehouse.plantId() != order.getPlantId()) {
      throw new BizException(ErrorCode.GOODS_RECEIPT_WAREHOUSE_INVALID);
    }
    List<PreparedLine> prepared = prepare(poId, warehouseId, commands);

    GoodsReceipt receipt = new GoodsReceipt();
    receipt.setDocNo(P2pSupport.docNo("GR"));
    receipt.setPoId(poId);
    receipt.setCompanyId(order.getCompanyId());
    receipt.setWarehouseId(warehouseId);
    receipt.setStatus(GoodsReceiptStatus.POSTED);
    receipt.setCreatedBy(operatorId);
    receipt.setVersion(0);
    goodsReceiptMapper.insert(receipt);

    BigDecimal totalQty = BigDecimal.ZERO;
    BigDecimal netAmount = BigDecimal.ZERO;
    List<GoodsReceivedLine> eventLines = new ArrayList<>();
    for (PreparedLine line : prepared) {
      GoodsReceiptLine entity = new GoodsReceiptLine();
      entity.setGrId(receipt.getId());
      entity.setLineNo(line.lineNo());
      entity.setPoLineId(line.poLine().getId());
      entity.setItemId(line.poLine().getItemId());
      entity.setQty(line.qty());
      entity.setUom(line.poLine().getUom());
      entity.setLotNo(line.lotNo());
      entity.setLocationId(line.locationId());
      lineMapper.insert(entity);
      BigDecimal lineAmount =
          P2pSupport.lineAmount(line.qty(), line.poLine().getPrice());
      totalQty = P2pSupport.sum(totalQty, line.qty());
      netAmount = P2pSupport.sum(netAmount, lineAmount);
      eventLines.add(
          new GoodsReceivedLine(
              entity.getId(),
              line.poLine().getId(),
              line.poLine().getItemId(),
              line.qty(),
              line.poLine().getUom(),
              line.poLine().getPrice(),
              lineAmount,
              line.lotNo(),
              line.locationId(),
              line.productionDate(),
              line.expiryDate(),
              line.serials()));
      if (purchaseOrderLineMapper.increaseReceived(
              line.poLine().getId(), poId, line.qty())
          == 0) {
        throw new BizException(ErrorCode.GOODS_RECEIPT_QTY_EXCEEDS_OPEN);
      }
    }
    purchaseOrderMapper.refreshReceiptStatus(poId);
    PurchaseOrderStatus statusAfter =
        purchaseOrderMapper.findById(poId).map(PurchaseOrder::getStatus).orElse(null);

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("poId", poId);
    detail.put("poDocNo", order.getDocNo());
    detail.put("warehouseId", warehouseId);
    detail.put("lineCount", prepared.size());
    detail.put("totalQty", totalQty);
    detail.put("poStatusAfter", statusAfter);
    auditFacade.record(
        AuditAction.GOODS_RECEIPT_POST,
        AuditTargetType.GOODS_RECEIPT,
        receipt.getId(),
        detail);
    eventPublisher.publish(
        new GoodsReceivedEvent(
            receipt.getId(),
            poId,
            order.getCompanyId(),
            order.getSupplierId(),
            warehouseId,
            order.getCurrency(),
            netAmount,
            java.time.LocalDate.now(),
            eventLines));
    log.info(
        "收货单 {} 过账：PO {}，{} 行，总数量 {}", receipt.getDocNo(), order.getDocNo(), prepared.size(), totalQty);
    return presenter.goodsReceipt(load(receipt.getId()));
  }

  /**
   * 收货冲销（W3）：回退 PO 已收数量、发 REVERSAL 库存事件、把 GR 置为 REVERSED。
   *
   * <p>已开票的收货不能冲销：冲销后已收量会低于已开票量，三单匹配与 AP 会失去事实基础。
   * 库存不足同样让整个事务回滚（2301），不允许负库存。
   */
  @Transactional
  public GoodsReceiptView reverse(long id, long operatorId, String reason, int version) {
    String normalizedReason = P2pSupport.required(reason, 200, "冲销原因");
    GoodsReceipt receipt =
        goodsReceiptMapper
            .findByIdForUpdate(id)
            .orElseThrow(() -> new BizException(ErrorCode.GOODS_RECEIPT_NOT_FOUND));
    if (receipt.getStatus() != GoodsReceiptStatus.POSTED) {
      throw new BizException(ErrorCode.GOODS_RECEIPT_STATUS_INVALID);
    }
    PurchaseOrder order =
        purchaseOrderMapper
            .findByIdForUpdate(receipt.getPoId())
            .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
    if (order.getStatus() != PurchaseOrderStatus.SENT
        && order.getStatus() != PurchaseOrderStatus.PARTIAL_RECEIVED
        && order.getStatus() != PurchaseOrderStatus.RECEIVED) {
      throw new BizException(ErrorCode.GOODS_RECEIPT_STATUS_INVALID, "采购订单当前状态不可冲销");
    }
    List<GoodsReceiptLine> lines = lineMapper.findByGrId(id);
    if (lines.isEmpty()) {
      throw new BizException(ErrorCode.GOODS_RECEIPT_LINE_INVALID, "收货单没有可冲销的行");
    }
    List<GoodsReceivedLine> eventLines = new ArrayList<>();
    BigDecimal totalQty = BigDecimal.ZERO;
    for (GoodsReceiptLine line : lines) {
      PurchaseOrderLine poLine =
          purchaseOrderLineMapper
              .findByIdAndPo(line.getPoLineId(), receipt.getPoId())
              .orElseThrow(
                  () -> new BizException(ErrorCode.GOODS_RECEIPT_LINE_INVALID, "订单行已被移除"));
      if (poLine.getReceivedQty().subtract(line.getQty()).compareTo(poLine.getInvoicedQty()) < 0) {
        throw new BizException(ErrorCode.GOODS_RECEIPT_INVOICED);
      }
      if (purchaseOrderLineMapper.decreaseReceived(
              line.getPoLineId(), receipt.getPoId(), line.getQty())
          == 0) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
      BigDecimal lineAmount = P2pSupport.lineAmount(line.getQty(), poLine.getPrice());
      totalQty = P2pSupport.sum(totalQty, line.getQty());
      eventLines.add(
          new GoodsReceivedLine(
              line.getId(),
              line.getPoLineId(),
              line.getItemId(),
              line.getQty(),
              line.getUom(),
              poLine.getPrice(),
              lineAmount,
              line.getLotNo(),
              line.getLocationId(),
              null,
              null,
              java.util.List.of()));
    }
    if (goodsReceiptMapper.reverse(id, operatorId, normalizedReason, version) == 0) {
      GoodsReceipt current =
          goodsReceiptMapper
              .findById(id)
              .orElseThrow(() -> new BizException(ErrorCode.GOODS_RECEIPT_NOT_FOUND));
      if (current.getVersion() != version) {
        throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
      }
      throw new BizException(ErrorCode.GOODS_RECEIPT_STATUS_INVALID);
    }
    purchaseOrderMapper.refreshReceiptStatusAfterReversal(receipt.getPoId());
    PurchaseOrderStatus statusAfter =
        purchaseOrderMapper.findById(receipt.getPoId()).map(PurchaseOrder::getStatus).orElse(null);

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("poId", receipt.getPoId());
    detail.put("poDocNo", order.getDocNo());
    detail.put("warehouseId", receipt.getWarehouseId());
    detail.put("lineCount", lines.size());
    detail.put("totalQty", totalQty);
    detail.put("reason", AuditDetails.commentSummary(normalizedReason));
    detail.put("poStatusAfter", statusAfter);
    auditFacade.record(
        AuditAction.GOODS_RECEIPT_REVERSE,
        AuditTargetType.GOODS_RECEIPT,
        id,
        detail);
    eventPublisher.publish(
        new GoodsReceiptReversedEvent(
            id,
            receipt.getPoId(),
            receipt.getCompanyId(),
            receipt.getWarehouseId(),
            order.getCurrency(),
            java.time.LocalDate.now(),
            normalizedReason,
            eventLines));
    log.info("收货单 {} 已冲销：{} 行，总数量 {}", receipt.getDocNo(), lines.size(), totalQty);
    return presenter.goodsReceipt(load(id));
  }

  public GoodsReceipt load(long id) {
    return goodsReceiptMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.GOODS_RECEIPT_NOT_FOUND));
  }

  private List<PreparedLine> prepare(
      long poId, long warehouseId, List<GoodsReceiptLineCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.GOODS_RECEIPT_LINE_INVALID, "至少需要一行收货明细");
    }
    Set<Long> seen = new HashSet<>();
    List<PreparedLine> prepared = new ArrayList<>();
    for (GoodsReceiptLineCommand command : commands) {
      if (command == null || command.poLineId() <= 0) {
        throw new BizException(ErrorCode.GOODS_RECEIPT_LINE_INVALID, "订单行不能为空");
      }
      if (!seen.add(command.poLineId())) {
        throw new BizException(ErrorCode.GOODS_RECEIPT_LINE_INVALID, "同一订单行不能重复收货");
      }
      PurchaseOrderLine poLine =
          purchaseOrderLineMapper
              .findByIdAndPo(command.poLineId(), poId)
              .orElseThrow(
                  () -> new BizException(ErrorCode.GOODS_RECEIPT_LINE_INVALID, "订单行不属于该订单"));
      BigDecimal qty = P2pSupport.qty(command.qty());
      if (qty == null || qty.signum() <= 0) {
        throw new BizException(ErrorCode.GOODS_RECEIPT_LINE_INVALID, "收货数量必须大于 0");
      }
      if (poLine.getReceivedQty().add(qty).compareTo(poLine.getQty()) > 0) {
        throw new BizException(ErrorCode.GOODS_RECEIPT_QTY_EXCEEDS_OPEN);
      }
      ItemBrief item =
          masterDataQueryFacade
              .findItem(poLine.getItemId())
              .orElseThrow(() -> new BizException(ErrorCode.ITEM_NOT_FOUND));
      String lotNo = P2pSupport.optional(command.lotNo(), 64, "批次号");
      if (item.lotControlled() && lotNo == null) {
        throw new BizException(ErrorCode.INVENTORY_LOT_REQUIRED, "物料 " + item.code() + " 必须指定批次");
      }
      if (command.productionDate() != null
          && command.expiryDate() != null
          && command.expiryDate().isBefore(command.productionDate())) {
        throw new BizException(ErrorCode.INVENTORY_LOT_DATE_INVALID);
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
      List<String> serials = P2pSupport.serials(item.serialControlled(), qty, command.serials());
      prepared.add(
          new PreparedLine(
              poLine,
              qty,
              prepared.size() + 1,
              lotNo,
              command.productionDate(),
              command.expiryDate(),
              locationId,
              serials));
    }
    return prepared;
  }

  private record PreparedLine(
      PurchaseOrderLine poLine,
      BigDecimal qty,
      int lineNo,
      String lotNo,
      java.time.LocalDate productionDate,
      java.time.LocalDate expiryDate,
      long locationId,
      List<String> serials) {}
}
