package com.spe.context.procurement.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.procurement.api.PurchaseOrderStatus;
import com.spe.context.procurement.api.SupplierInvoiceStatus;
import com.spe.context.procurement.api.events.SupplierInvoiceApprovedEvent;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商发票（W4；docs/erp/32 §4、50 §3）。
 *
 * <p>录入即跑三单匹配；异常发票仍可保存，但批准必须填原因（2206）。批准是唯一会累加
 * `purchase_order_line.invoiced_qty` 的路径，且录入人不能批准本人发票（4004）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierInvoiceService {

  private final SupplierInvoiceMapper invoiceMapper;
  private final SupplierInvoiceLineMapper lineMapper;
  private final P2pMatchResultMapper matchResultMapper;
  private final PurchaseOrderMapper purchaseOrderMapper;
  private final PurchaseOrderLineMapper purchaseOrderLineMapper;
  private final GoodsReceiptMapper goodsReceiptMapper;
  private final InvoiceMatchService matchService;
  private final AuditFacade auditFacade;
  private final EventPublisher eventPublisher;
  private final InvoicePresenter presenter;

  @Transactional(readOnly = true)
  public PageResult<InvoiceSummaryView> page(
      int page, int size, Long companyId, Long supplierId, String status, String keyword) {
    PageQuery query = PageQuery.of(page, size);
    SupplierInvoiceStatus parsed = P2pSupport.parse(SupplierInvoiceStatus.class, status, "状态");
    String like = P2pSupport.keyword(keyword);
    List<SupplierInvoice> rows =
        invoiceMapper.findPage(companyId, supplierId, parsed, like, query.offset(), query.size());
    return PageResult.of(
        presenter.invoices(rows),
        invoiceMapper.countBy(companyId, supplierId, parsed, like),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public InvoiceView detail(long id) {
    return presenter.invoice(load(id));
  }

  @Transactional
  public InvoiceView create(
      long operatorId,
      long poId,
      String supplierInvoiceNo,
      LocalDate invoiceDate,
      LocalDate dueDate,
      String currency,
      BigDecimal taxAmount,
      List<InvoiceLineCommand> commands) {
    // 锁订单头：与取消串行，避免“取消检查通过后又插进一张发票”。
    PurchaseOrder order =
        purchaseOrderMapper
            .findByIdForUpdate(poId)
            .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
    if (order.getStatus() != PurchaseOrderStatus.SENT
        && order.getStatus() != PurchaseOrderStatus.PARTIAL_RECEIVED
        && order.getStatus() != PurchaseOrderStatus.RECEIVED) {
      throw new BizException(ErrorCode.PAYMENT_BLOCKED, "采购订单当前状态不允许开票");
    }
    String normalizedCurrency = P2pSupport.upper(currency);
    if (normalizedCurrency != null
        && !normalizedCurrency.isEmpty()
        && !order.getCurrency().equals(normalizedCurrency)) {
      throw new BizException(ErrorCode.PROCUREMENT_CURRENCY_INVALID, "发票币种必须与订单一致");
    }
    String normalizedInvoiceNo = P2pSupport.required(supplierInvoiceNo, 64, "供应商发票号");
    if (invoiceMapper.countBySupplierAndNo(order.getSupplierId(), normalizedInvoiceNo) > 0) {
      throw new BizException(ErrorCode.SUPPLIER_INVOICE_DUPLICATE);
    }
    BigDecimal normalizedTax = P2pSupport.price(taxAmount == null ? BigDecimal.ZERO : taxAmount);
    if (normalizedTax.signum() < 0) {
      throw new BizException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "税额不能为负");
    }
    Normalized normalized = normalize(poId, commands);
    P2pSupport.ensureAmount(
        normalized.net().add(normalizedTax), ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "价税合计");

    SupplierInvoice invoice = new SupplierInvoice();
    invoice.setDocNo(P2pSupport.docNo("IR"));
    invoice.setSupplierInvoiceNo(normalizedInvoiceNo);
    invoice.setCompanyId(order.getCompanyId());
    invoice.setSupplierId(order.getSupplierId());
    invoice.setPoId(poId);
    invoice.setInvoiceDate(invoiceDate);
    invoice.setDueDate(dueDate);
    invoice.setCurrency(order.getCurrency());
    invoice.setNetAmount(normalized.net());
    invoice.setTaxAmount(normalizedTax);
    invoice.setTotalAmount(normalized.net().add(normalizedTax));
    invoice.setPaidAmount(BigDecimal.ZERO);
    invoice.setStatus(SupplierInvoiceStatus.RECEIVED);
    invoice.setCreatedBy(operatorId);
    invoice.setVersion(0);
    try {
      invoiceMapper.insert(invoice);
    } catch (DuplicateKeyException ex) {
      throw new BizException(ErrorCode.SUPPLIER_INVOICE_DUPLICATE);
    }
    for (SupplierInvoiceLine line : normalized.lines()) {
      line.setInvoiceId(invoice.getId());
      lineMapper.insert(line);
    }
    P2pMatchResult match = runMatch(invoice, normalized.lines());
    if (invoiceMapper.updateMatchStatus(invoice.getId(), invoiceStatus(match), 0) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    log.info(
        "录入供应商发票 {}（{}），金额 {}，匹配 {}",
        invoice.getDocNo(),
        normalizedInvoiceNo,
        invoice.getTotalAmount(),
        match.getStatus());

    Map<String, Object> createDetail = new LinkedHashMap<>();
    createDetail.put("poId", poId);
    createDetail.put("supplierInvoiceNo", normalizedInvoiceNo);
    createDetail.put("lineCount", normalized.lines().size());
    createDetail.put("netAmount", normalized.net());
    createDetail.put("taxAmount", normalizedTax);
    createDetail.put("totalAmount", invoice.getTotalAmount());
    createDetail.put("matchStatus", match.getStatus());
    auditFacade.record(
        AuditAction.SUPPLIER_INVOICE_CREATE,
        AuditTargetType.SUPPLIER_INVOICE,
        invoice.getId(),
        createDetail);
    return presenter.invoice(load(invoice.getId()));
  }

  @Transactional
  public InvoiceView rematch(long id, int version) {
    SupplierInvoice invoice = load(id);
    if (invoice.getStatus() != SupplierInvoiceStatus.RECEIVED
        && invoice.getStatus() != SupplierInvoiceStatus.MATCHED
        && invoice.getStatus() != SupplierInvoiceStatus.EXCEPTION) {
      throw new BizException(ErrorCode.SUPPLIER_INVOICE_STATUS_INVALID);
    }
    List<SupplierInvoiceLine> lines = lineMapper.findByInvoiceId(id);
    P2pMatchResult match = runMatch(invoice, lines);
    if (invoiceMapper.updateMatchStatus(id, invoiceStatus(match), version) == 0) {
      throw conflictOrStatus(id, version);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("matchStatus", match.getStatus());
    detail.put("priceVariance", match.getPriceVariance());
    detail.put("qtyVariance", match.getQtyVariance());
    detail.put("detail", match.getDetail());
    auditFacade.record(
        AuditAction.SUPPLIER_INVOICE_MATCH,
        AuditTargetType.SUPPLIER_INVOICE,
        id,
        detail);
    return presenter.invoice(load(id));
  }

  @Transactional
  public InvoiceView approve(long id, long operatorId, String comment, int version) {
    SupplierInvoice invoice = load(id);
    if (invoice.getStatus() != SupplierInvoiceStatus.MATCHED
        && invoice.getStatus() != SupplierInvoiceStatus.EXCEPTION) {
      throw new BizException(ErrorCode.SUPPLIER_INVOICE_STATUS_INVALID);
    }
    if (invoice.getCreatedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    PurchaseOrder order =
        purchaseOrderMapper
            .findByIdForUpdate(invoice.getPoId())
            .orElseThrow(() -> new BizException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
    if (order.getStatus() != PurchaseOrderStatus.SENT
        && order.getStatus() != PurchaseOrderStatus.PARTIAL_RECEIVED
        && order.getStatus() != PurchaseOrderStatus.RECEIVED) {
      throw new BizException(ErrorCode.SUPPLIER_INVOICE_STATUS_INVALID, "采购订单状态不可批准发票");
    }
    // SoD：收货人不能审批该 PO 的发票。
    if (goodsReceiptMapper.countPostedByPoAndCreator(invoice.getPoId(), operatorId) > 0) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "收货人不能审批该发票");
    }
    String normalizedComment = P2pSupport.optional(comment, 500, "审批意见");
    if (invoice.getStatus() == SupplierInvoiceStatus.EXCEPTION && normalizedComment == null) {
      throw new BizException(ErrorCode.SUPPLIER_INVOICE_MISMATCH);
    }
    boolean matched = invoice.getStatus() == SupplierInvoiceStatus.MATCHED;
    if (invoiceMapper.approve(id, operatorId, version) == 0) {
      throw conflictOrStatus(id, version);
    }
    for (SupplierInvoiceLine line : lineMapper.findByInvoiceId(id)) {
      if (matched) {
        PurchaseOrderLine poLine =
            purchaseOrderLineMapper
                .findByIdAndPo(line.getPoLineId(), invoice.getPoId())
                .orElseThrow(
                    () -> new BizException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "订单行已被移除"));
        BigDecimal maxQty = matchService.maxInvoiceableQty(poLine, invoice.getCompanyId());
        if (purchaseOrderLineMapper.increaseInvoicedGuarded(
                line.getPoLineId(), invoice.getPoId(), line.getQty(), maxQty)
            == 0) {
          throw new BizException(ErrorCode.SUPPLIER_INVOICE_QTY_EXCEEDS_RECEIVED);
        }
      } else if (purchaseOrderLineMapper.increaseInvoiced(
              line.getPoLineId(), invoice.getPoId(), line.getQty())
          == 0) {
        throw new BizException(
            ErrorCode.SUPPLIER_INVOICE_QTY_EXCEEDS_RECEIVED, "异常发票不得超过订单行数量");
      }
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("matchStatus", invoice.getStatus());
    detail.put("totalAmount", invoice.getTotalAmount());
    detail.put("comment", AuditDetails.commentSummary(normalizedComment));
    auditFacade.record(
        AuditAction.SUPPLIER_INVOICE_APPROVE,
        AuditTargetType.SUPPLIER_INVOICE,
        id,
        detail);
    eventPublisher.publish(
        new SupplierInvoiceApprovedEvent(
            id,
            invoice.getCompanyId(),
            invoice.getCurrency(),
            invoice.getNetAmount(),
            invoice.getTaxAmount(),
            invoice.getTotalAmount(),
            invoice.getInvoiceDate()));
    return presenter.invoice(load(id));
  }

  public SupplierInvoice load(long id) {
    return invoiceMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_INVOICE_NOT_FOUND));
  }

  private P2pMatchResult runMatch(SupplierInvoice invoice, List<SupplierInvoiceLine> lines) {
    P2pMatchResult result = matchService.match(invoice, lines);
    P2pMatchResult existing = matchResultMapper.findByInvoiceId(invoice.getId()).orElse(null);
    if (existing == null) {
      matchResultMapper.insert(result);
    } else {
      result.setId(existing.getId());
      matchResultMapper.updateByInvoiceId(result);
    }
    return result;
  }

  private static SupplierInvoiceStatus invoiceStatus(P2pMatchResult result) {
    return result.getStatus() == MatchStatus.MATCHED
        ? SupplierInvoiceStatus.MATCHED
        : SupplierInvoiceStatus.EXCEPTION;
  }

  private Normalized normalize(long poId, List<InvoiceLineCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "至少需要一行发票明细");
    }
    Set<Long> seen = new HashSet<>();
    List<SupplierInvoiceLine> lines = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    for (InvoiceLineCommand command : commands) {
      if (command == null || command.poLineId() <= 0) {
        throw new BizException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "订单行不能为空");
      }
      if (!seen.add(command.poLineId())) {
        throw new BizException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "同一订单行不能重复开票");
      }
      PurchaseOrderLine poLine =
          purchaseOrderLineMapper
              .findByIdAndPo(command.poLineId(), poId)
              .orElseThrow(
                  () -> new BizException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "订单行不属于该订单"));
      BigDecimal qty = P2pSupport.qty(command.qty());
      if (qty == null || qty.signum() <= 0) {
        throw new BizException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "开票数量必须大于 0");
      }
      BigDecimal price = P2pSupport.price(command.unitPrice());
      if (price == null || price.signum() < 0) {
        throw new BizException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "开票单价不能为负");
      }
      SupplierInvoiceLine line = new SupplierInvoiceLine();
      line.setLineNo(lines.size() + 1);
      line.setPoLineId(poLine.getId());
      line.setItemId(poLine.getItemId());
      line.setQty(qty);
      line.setUnitPrice(price);
      line.setAmount(P2pSupport.lineAmount(qty, price));
      line.setTaxAmount(BigDecimal.ZERO);
      P2pSupport.ensureAmount(line.getAmount(), ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "行金额");
      net = P2pSupport.sum(net, line.getAmount());
      P2pSupport.ensureAmount(net, ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "发票净额");
      lines.add(line);
    }
    return new Normalized(lines, net);
  }

  private BizException conflictOrStatus(long id, int version) {
    SupplierInvoice current = load(id);
    if (current.getVersion() != version) {
      return new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    return new BizException(ErrorCode.SUPPLIER_INVOICE_STATUS_INVALID);
  }

  private record Normalized(List<SupplierInvoiceLine> lines, BigDecimal net) {}
}
