package com.spe.context.procurement.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.masterdata.api.PlantBrief;
import com.spe.context.procurement.api.ApprovalDocType;
import com.spe.context.procurement.api.PurchaseOrderStatus;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
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
 * 采购订单（W2；docs/erp/32 §4）。
 *
 * <p>价格控制：若存在有效采购价目，PO 单价不得超过价目 + max(1%, 0.01)（2202）。审批矩阵按订单金额
 * 裁定；创建人不能审批本人 PO（4004）。PR 转单先原子把 PR 置为 CONVERTED，再插 PO——重复点击只会
 * 成功一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

  private final PurchaseOrderMapper purchaseOrderMapper;
  private final PurchaseOrderLineMapper lineMapper;
  private final SupplierInvoiceMapper supplierInvoiceMapper;
  private final ContractConsumptionMapper contractConsumptionMapper;
  private final PurchaseRequisitionLineMapper requisitionLineMapper;
  private final RequisitionService requisitionService;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final ApprovalMatrixService approvalMatrixService;
  private final AuditFacade auditFacade;
  private final ProcurementPresenter presenter;

  @Transactional(readOnly = true)
  public PageResult<PurchaseOrderSummaryView> page(
      int page, int size, Long companyId, Long supplierId, String status, String keyword) {
    PageQuery query = PageQuery.of(page, size);
    PurchaseOrderStatus parsed = P2pSupport.parse(PurchaseOrderStatus.class, status, "状态");
    String like = P2pSupport.keyword(keyword);
    List<PurchaseOrder> rows =
        purchaseOrderMapper.findPage(
            companyId, supplierId, parsed, like, query.offset(), query.size());
    return PageResult.of(
        presenter.purchaseOrders(rows),
        purchaseOrderMapper.countBy(companyId, supplierId, parsed, like),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public PurchaseOrderView detail(long id) {
    return presenter.purchaseOrder(load(id));
  }

  @Transactional
  public PurchaseOrderView create(
      long operatorId,
      long companyId,
      long supplierId,
      long plantId,
      String currency,
      String paymentTerms,
      List<PurchaseOrderLineCommand> commands) {
    CompanyBrief company = requisitionService.requireActiveCompany(companyId);
    String normalizedCurrency = requisitionService.resolveCurrency(company, currency);
    requireSupplier(supplierId);
    requirePlant(plantId, companyId);
    Normalized normalized = normalize(commands, normalizedCurrency);

    PurchaseOrder order = new PurchaseOrder();
    order.setDocNo(P2pSupport.docNo("PO"));
    order.setCompanyId(companyId);
    order.setSupplierId(supplierId);
    order.setPlantId(plantId);
    order.setCurrency(normalizedCurrency);
    order.setPaymentTerms(P2pSupport.optional(paymentTerms, 32, "付款条件"));
    order.setAmount(normalized.total());
    order.setStatus(PurchaseOrderStatus.DRAFT);
    order.setCreatedBy(operatorId);
    order.setVersion(0);
    purchaseOrderMapper.insert(order);
    insertLines(order.getId(), normalized.lines());

    auditCreate(order, null);
    log.info("创建采购订单 {}（id={}，金额={}）", order.getDocNo(), order.getId(), normalized.total());
    return presenter.purchaseOrder(load(order.getId()));
  }

  /** PR 已审批 → 生成 DRAFT PO；PR 在同一事务内转为 CONVERTED。 */
  @Transactional
  public PurchaseOrderView createFromRequisition(
      long requisitionId,
      long operatorId,
      long supplierId,
      long plantId,
      String paymentTerms,
      int requisitionVersion) {
    PurchaseRequisition requisition = requisitionService.load(requisitionId);
    if (requisition.getStatus() != com.spe.context.procurement.api.RequisitionStatus.APPROVED) {
      throw new BizException(ErrorCode.PURCHASE_REQUISITION_SOURCE_INVALID);
    }
    if (purchaseOrderMapper.countActiveBySourcePr(requisitionId) > 0) {
      throw new BizException(ErrorCode.PURCHASE_REQUISITION_SOURCE_INVALID);
    }
    requireSupplier(supplierId);
    requirePlant(plantId, requisition.getCompanyId());

    List<PurchaseRequisitionLine> prLines = requisitionLineMapper.findByRequisitionId(requisitionId);
    if (prLines.isEmpty()) {
      throw new BizException(ErrorCode.PURCHASE_REQUISITION_SOURCE_INVALID);
    }
    List<PurchaseOrderLineCommand> commands =
        prLines.stream()
            .map(
                line ->
                    new PurchaseOrderLineCommand(
                        line.getItemId(), line.getQty(), line.getUom(), line.getEstimatedPrice()))
            .toList();
    Normalized normalized = normalize(commands, requisition.getCurrency());

    // 先占用 PR（状态守卫 + 版本守卫），再插 PO；事务回滚时一起回退。
    requisitionService.markConverted(requisitionId, requisitionVersion);

    PurchaseOrder order = new PurchaseOrder();
    order.setDocNo(P2pSupport.docNo("PO"));
    order.setCompanyId(requisition.getCompanyId());
    order.setSupplierId(supplierId);
    order.setPlantId(plantId);
    order.setSourcePrId(requisitionId);
    order.setCurrency(requisition.getCurrency());
    order.setPaymentTerms(P2pSupport.optional(paymentTerms, 32, "付款条件"));
    order.setAmount(normalized.total());
    order.setStatus(PurchaseOrderStatus.DRAFT);
    order.setCreatedBy(operatorId);
    order.setVersion(0);
    purchaseOrderMapper.insert(order);
    insertLines(order.getId(), normalized.lines());

    auditCreate(order, requisitionId);
    Map<String, Object> convertDetail = new LinkedHashMap<>();
    convertDetail.put("poId", order.getId());
    convertDetail.put("poDocNo", order.getDocNo());
    convertDetail.put("supplierId", supplierId);
    convertDetail.put("lineCount", normalized.lines().size());
    convertDetail.put("amount", normalized.total());
    auditFacade.record(
        AuditAction.PURCHASE_REQUISITION_CONVERT,
        AuditTargetType.PURCHASE_REQUISITION,
        requisitionId,
        convertDetail);
    log.info(
        "请购单 {} 转采购订单 {}", requisition.getDocNo(), order.getDocNo());
    return presenter.purchaseOrder(load(order.getId()));
  }

  @Transactional
  public PurchaseOrderView update(
      long id,
      long operatorId,
      long supplierId,
      long plantId,
      String currency,
      String paymentTerms,
      List<PurchaseOrderLineCommand> commands,
      int version) {
    PurchaseOrder current = load(id);
    if (current.getCreatedBy() != operatorId) {
      throw new BizException(ErrorCode.FORBIDDEN, "只有创建人可以修改该采购订单");
    }
    requireStatus(current, PurchaseOrderStatus.DRAFT);
    requireSupplier(supplierId);
    requirePlant(plantId, current.getCompanyId());
    CompanyBrief company = requisitionService.requireActiveCompany(current.getCompanyId());
    String normalizedCurrency = requisitionService.resolveCurrency(company, currency);
    Normalized normalized = normalize(commands, normalizedCurrency);

    if (purchaseOrderMapper.updateDraft(
            id,
            supplierId,
            plantId,
            normalizedCurrency,
            P2pSupport.optional(paymentTerms, 32, "付款条件"),
            normalized.total(),
            version)
        == 0) {
      throw conflictOrStatus(id, PurchaseOrderStatus.DRAFT, version);
    }
    lineMapper.deleteByPoId(id);
    insertLines(id, normalized.lines());

    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "supplierId", current.getSupplierId(), supplierId);
    AuditDetails.putIfChanged(detail, "plantId", current.getPlantId(), plantId);
    AuditDetails.putIfChanged(detail, "currency", current.getCurrency(), normalizedCurrency);
    AuditDetails.putIfChanged(detail, "amount", current.getAmount(), normalized.total());
    detail.put("lineCount", normalized.lines().size());
    auditFacade.record(
        AuditAction.PURCHASE_ORDER_UPDATE, AuditTargetType.PURCHASE_ORDER, id, detail);
    return presenter.purchaseOrder(load(id));
  }

  @Transactional
  public PurchaseOrderView approve(long id, long operatorId, boolean admin, int version) {
    PurchaseOrder current = load(id);
    requireStatus(current, PurchaseOrderStatus.DRAFT);
    if (current.getCreatedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    P2pApprovalRule rule =
        approvalMatrixService.requireSatisfied(
            ApprovalDocType.PO, current.getCompanyId(), current.getAmount(), admin);
    if (purchaseOrderMapper.approve(id, operatorId, version) == 0) {
      throw conflictOrStatus(id, PurchaseOrderStatus.DRAFT, version);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("amount", current.getAmount());
    detail.put("supplierId", current.getSupplierId());
    detail.put("requiredRole", rule.getRequiredRole());
    detail.put("approverRole", admin ? "ADMIN" : "MANAGER");
    auditFacade.record(
        AuditAction.PURCHASE_ORDER_APPROVE, AuditTargetType.PURCHASE_ORDER, id, detail);
    return presenter.purchaseOrder(load(id));
  }

  @Transactional
  public PurchaseOrderView reject(
      long id, long operatorId, boolean admin, String comment, int version) {
    String normalizedComment = P2pSupport.required(comment, 500, "驳回意见");
    PurchaseOrder current = load(id);
    requireStatus(current, PurchaseOrderStatus.DRAFT);
    if (current.getCreatedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    P2pApprovalRule rule =
        approvalMatrixService.requireSatisfied(
            ApprovalDocType.PO, current.getCompanyId(), current.getAmount(), admin);
    if (purchaseOrderMapper.reject(id, operatorId, version) == 0) {
      throw conflictOrStatus(id, PurchaseOrderStatus.DRAFT, version);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("amount", current.getAmount());
    detail.put("requiredRole", rule.getRequiredRole());
    detail.put("comment", AuditDetails.commentSummary(normalizedComment));
    auditFacade.record(
        AuditAction.PURCHASE_ORDER_REJECT, AuditTargetType.PURCHASE_ORDER, id, detail);
    return presenter.purchaseOrder(load(id));
  }

  @Transactional
  public PurchaseOrderView send(long id, int version) {
    PurchaseOrder current = load(id);
    requireStatus(current, PurchaseOrderStatus.APPROVED);
    if (purchaseOrderMapper.send(id, version) == 0) {
      throw conflictOrStatus(id, PurchaseOrderStatus.APPROVED, version);
    }
    auditFacade.record(
        AuditAction.PURCHASE_ORDER_SEND,
        AuditTargetType.PURCHASE_ORDER,
        id,
        Map.of("docNo", current.getDocNo(), "amount", current.getAmount()));
    return presenter.purchaseOrder(load(id));
  }

  @Transactional
  public PurchaseOrderView cancel(long id, int version) {
    // 锁订单头：与收货/开票创建串行，避免“取消检查通过后，收货/发票又插进来”。
    PurchaseOrder current =
        purchaseOrderMapper
            .findByIdForUpdate(id)
            .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
    if (current.getStatus() == PurchaseOrderStatus.PARTIAL_RECEIVED
        || current.getStatus() == PurchaseOrderStatus.RECEIVED
        || current.getStatus() == PurchaseOrderStatus.CLOSED) {
      throw new BizException(ErrorCode.PURCHASE_ORDER_STATUS_INVALID, "已有收货的订单不能取消");
    }
    if (supplierInvoiceMapper.countActiveByPo(id) > 0) {
      throw new BizException(ErrorCode.PURCHASE_ORDER_STATUS_INVALID, "已有发票的订单不能取消");
    }
    if (purchaseOrderMapper.cancel(id, version) == 0) {
      throw conflictOrStatus(id, current.getStatus(), version);
    }
    // 合同释放出的订单取消后必须释放额度，否则合同消耗永久占用。
    contractConsumptionMapper.deleteByPo(id);
    auditFacade.record(
        AuditAction.PURCHASE_ORDER_CANCEL,
        AuditTargetType.PURCHASE_ORDER,
        id,
        Map.of("docNo", current.getDocNo(), "from", current.getStatus().name()));
    return presenter.purchaseOrder(load(id));
  }

  public PurchaseOrder load(long id) {
    return purchaseOrderMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
  }

  private void auditCreate(PurchaseOrder order, Long sourcePrId) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("companyId", order.getCompanyId());
    detail.put("supplierId", order.getSupplierId());
    detail.put("plantId", order.getPlantId());
    if (sourcePrId != null) {
      detail.put("sourcePrId", sourcePrId);
    }
    detail.put("amount", order.getAmount());
    detail.put("lineCount", lineMapper.findByPoId(order.getId()).size());
    auditFacade.record(
        AuditAction.PURCHASE_ORDER_CREATE, AuditTargetType.PURCHASE_ORDER, order.getId(), detail);
  }

  private void requireSupplier(long supplierId) {
    SupplierBrief supplier =
        supplierQueryFacade
            .findById(supplierId)
            .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_SUPPLIER_INVALID));
    if (!supplier.enabled()) {
      throw new BizException(ErrorCode.PURCHASE_ORDER_SUPPLIER_INVALID);
    }
  }

  private void requirePlant(long plantId, long companyId) {
    PlantBrief plant =
        masterDataQueryFacade
            .findPlant(plantId)
            .orElseThrow(() -> new BizException(ErrorCode.PLANT_NOT_FOUND));
    if (!plant.active() || plant.companyId() != companyId) {
      throw new BizException(ErrorCode.MASTER_DATA_HIERARCHY_INVALID);
    }
  }

  private Normalized normalize(List<PurchaseOrderLineCommand> commands, String currency) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.PURCHASE_ORDER_LINE_INVALID, "至少需要一行订单明细");
    }
    Set<String> keys = new HashSet<>();
    List<PurchaseOrderLine> lines = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    for (PurchaseOrderLineCommand command : commands) {
      if (command == null || command.itemId() <= 0) {
        throw new BizException(ErrorCode.PURCHASE_ORDER_LINE_INVALID, "物料不能为空");
      }
      ItemBrief item =
          masterDataQueryFacade
              .findItem(command.itemId())
              .orElseThrow(
                  () ->
                      new BizException(
                          ErrorCode.PURCHASE_ORDER_LINE_INVALID,
                          "物料不存在：" + command.itemId()));
      if (!item.active()) {
        throw new BizException(ErrorCode.PURCHASE_ORDER_LINE_INVALID, "物料已停用：" + item.code());
      }
      BigDecimal qty = P2pSupport.qty(command.qty());
      if (qty == null || qty.signum() <= 0) {
        throw new BizException(ErrorCode.PURCHASE_ORDER_LINE_INVALID, "数量必须大于 0");
      }
      BigDecimal price = P2pSupport.price(command.price());
      if (price == null || price.signum() < 0) {
        throw new BizException(ErrorCode.PURCHASE_ORDER_LINE_INVALID, "单价不能为负");
      }
      String uom = P2pSupport.upper(command.uom());
      if (uom == null || uom.isEmpty()) {
        uom = item.baseUom();
      }
      if (!keys.add(item.id() + "|" + uom)) {
        throw new BizException(
            ErrorCode.PURCHASE_ORDER_LINE_INVALID, "同一物料与单位不能重复：" + item.code());
      }
      BigDecimal reference =
          masterDataQueryFacade
              .findPurchasePrice(item.id(), uom, qty, currency, LocalDate.now())
              .orElse(null);
      if (!P2pSupport.withinPriceTolerance(price, reference)) {
        throw new BizException(
            ErrorCode.PURCHASE_ORDER_PRICE_EXCEEDS_REFERENCE,
            "物料 " + item.code() + " 单价 " + price + " 高于有效价目 " + reference);
      }
      PurchaseOrderLine line = new PurchaseOrderLine();
      line.setLineNo(lines.size() + 1);
      line.setItemId(item.id());
      line.setQty(qty);
      line.setUom(uom);
      line.setPrice(price);
      line.setAmount(P2pSupport.lineAmount(qty, price));
      line.setReceivedQty(BigDecimal.ZERO);
      line.setInvoicedQty(BigDecimal.ZERO);
      line.setReturnedQty(BigDecimal.ZERO);
      P2pSupport.ensureAmount(line.getAmount(), ErrorCode.PURCHASE_ORDER_LINE_INVALID, "行金额");
      total = P2pSupport.sum(total, line.getAmount());
      P2pSupport.ensureAmount(total, ErrorCode.PURCHASE_ORDER_LINE_INVALID, "订单金额");
      lines.add(line);
    }
    return new Normalized(lines, total);
  }

  private void insertLines(long poId, List<PurchaseOrderLine> lines) {
    for (PurchaseOrderLine line : lines) {
      line.setPoId(poId);
      lineMapper.insert(line);
    }
  }

  private void requireStatus(PurchaseOrder order, PurchaseOrderStatus expected) {
    if (order.getStatus() != expected) {
      throw new BizException(ErrorCode.PURCHASE_ORDER_STATUS_INVALID);
    }
  }

  private BizException conflictOrStatus(long id, PurchaseOrderStatus expected, int version) {
    PurchaseOrder current = load(id);
    if (current.getVersion() != version) {
      return new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    if (current.getStatus() != expected) {
      return new BizException(ErrorCode.PURCHASE_ORDER_STATUS_INVALID);
    }
    return new BizException(ErrorCode.CONCURRENT_CONFLICT);
  }

  private record Normalized(List<PurchaseOrderLine> lines, BigDecimal total) {}
}
