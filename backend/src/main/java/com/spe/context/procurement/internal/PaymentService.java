package com.spe.context.procurement.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.procurement.api.PaymentRequestStatus;
import com.spe.context.procurement.api.SupplierInvoiceStatus;
import com.spe.context.procurement.api.events.PaymentPaidEvent;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 付款申请（W4；docs/erp/32 §4、34 §5.1）。
 *
 * <p>付款以发票为唯一来源：发票必须 APPROVED/PARTIALLY_PAID；申请金额不能超过「发票总额 - 已付 -
 * 在途申请」。审批人不能是申请人，也不能是发票录入人（SoD：录入 vs 付款审批）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

  private final PaymentRequestMapper paymentMapper;
  private final SupplierInvoiceMapper invoiceMapper;
  private final GoodsReceiptMapper goodsReceiptMapper;
  private final AuditFacade auditFacade;
  private final EventPublisher eventPublisher;
  private final InvoicePresenter presenter;

  @Transactional(readOnly = true)
  public PageResult<PaymentSummaryView> page(
      int page, int size, Long companyId, Long invoiceId, String status, String keyword) {
    PageQuery query = PageQuery.of(page, size);
    PaymentRequestStatus parsed = P2pSupport.parse(PaymentRequestStatus.class, status, "状态");
    String like = P2pSupport.keyword(keyword);
    List<PaymentRequest> rows =
        paymentMapper.findPage(companyId, invoiceId, parsed, like, query.offset(), query.size());
    return PageResult.of(
        presenter.payments(rows),
        paymentMapper.countBy(companyId, invoiceId, parsed, like),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public PaymentView detail(long id) {
    return presenter.payment(load(id));
  }

  @Transactional
  public PaymentView create(long operatorId, long invoiceId, BigDecimal amount) {
    // FOR UPDATE：两个并发申请都会在同一行锁上串行计算未付额度，避免各自都通过前置检查。
    SupplierInvoice invoice =
        invoiceMapper
            .findByIdForUpdate(invoiceId)
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_INVOICE_NOT_FOUND));
    if (invoice.getStatus() != SupplierInvoiceStatus.APPROVED
        && invoice.getStatus() != SupplierInvoiceStatus.PARTIALLY_PAID) {
      throw new BizException(ErrorCode.PAYMENT_BLOCKED);
    }
    BigDecimal normalizedAmount = P2pSupport.price(amount);
    if (normalizedAmount == null || normalizedAmount.signum() <= 0) {
      throw new BizException(ErrorCode.PAYMENT_AMOUNT_INVALID);
    }
    BigDecimal outstanding =
        invoice
            .getTotalAmount()
            .subtract(invoice.getPaidAmount())
            .subtract(paymentMapper.sumActiveByInvoice(invoiceId));
    if (normalizedAmount.compareTo(outstanding) > 0) {
      throw new BizException(ErrorCode.PAYMENT_EXCEEDS_INVOICE);
    }

    PaymentRequest payment = new PaymentRequest();
    payment.setDocNo(P2pSupport.docNo("PAY"));
    payment.setCompanyId(invoice.getCompanyId());
    payment.setSupplierId(invoice.getSupplierId());
    payment.setInvoiceId(invoiceId);
    payment.setAmount(normalizedAmount);
    payment.setCurrency(invoice.getCurrency());
    payment.setStatus(PaymentRequestStatus.REQUESTED);
    payment.setRequestedBy(operatorId);
    payment.setVersion(0);
    paymentMapper.insert(payment);

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("invoiceId", invoiceId);
    detail.put("supplierId", invoice.getSupplierId());
    detail.put("amount", normalizedAmount);
    detail.put("outstandingBefore", outstanding);
    auditFacade.record(
        AuditAction.PAYMENT_REQUEST_CREATE, AuditTargetType.PAYMENT, payment.getId(), detail);
    log.info("创建付款申请 {}：发票 {}，金额 {}", payment.getDocNo(), invoice.getDocNo(), normalizedAmount);
    return presenter.payment(load(payment.getId()));
  }

  @Transactional
  public PaymentView approve(long id, long operatorId, int version) {
    PaymentRequest payment = load(id);
    if (payment.getStatus() != PaymentRequestStatus.REQUESTED) {
      throw new BizException(ErrorCode.PAYMENT_STATUS_INVALID);
    }
    if (payment.getRequestedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    SupplierInvoice invoice =
        invoiceMapper
            .findById(payment.getInvoiceId())
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_INVOICE_NOT_FOUND));
    if (invoice.getStatus() != SupplierInvoiceStatus.APPROVED
        && invoice.getStatus() != SupplierInvoiceStatus.PARTIALLY_PAID) {
      throw new BizException(ErrorCode.PAYMENT_BLOCKED, "发票当前状态不可付款");
    }
    if (invoice.getCreatedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "发票录入人不能审批该笔付款");
    }
    // SoD：收货人不能审批该 PO 的付款。
    if (goodsReceiptMapper.countPostedByPoAndCreator(invoice.getPoId(), operatorId) > 0) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "收货人不能审批该笔付款");
    }
    if (paymentMapper.approve(id, operatorId, version) == 0) {
      throw conflictOrStatus(id, version);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("invoiceId", payment.getInvoiceId());
    detail.put("amount", payment.getAmount());
    auditFacade.record(AuditAction.PAYMENT_APPROVE, AuditTargetType.PAYMENT, id, detail);
    return presenter.payment(load(id));
  }

  @Transactional
  public PaymentView pay(long id, long operatorId, int version) {
    PaymentRequest payment = load(id);
    if (payment.getStatus() != PaymentRequestStatus.APPROVED) {
      throw new BizException(ErrorCode.PAYMENT_STATUS_INVALID);
    }
    // 审批与执行分离：申请人、审批人、发票录入人、收货人都不能执行付款。
    if (payment.getRequestedBy() == operatorId || payment.getApprovedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "付款申请/审批人不能执行付款");
    }
    SupplierInvoice invoice =
        invoiceMapper
            .findById(payment.getInvoiceId())
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_INVOICE_NOT_FOUND));
    if (invoice.getCreatedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "发票录入人不能执行付款");
    }
    if (goodsReceiptMapper.countPostedByPoAndCreator(invoice.getPoId(), operatorId) > 0) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "收货人不能执行付款");
    }
    if (paymentMapper.markPaid(id, version) == 0) {
      throw conflictOrStatus(id, version);
    }
    if (invoiceMapper.registerPayment(payment.getInvoiceId(), payment.getAmount()) == 0) {
      throw new BizException(ErrorCode.PAYMENT_EXCEEDS_INVOICE, "发票已付金额发生变化，请刷新后重试");
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("invoiceId", payment.getInvoiceId());
    detail.put("amount", payment.getAmount());
    auditFacade.record(AuditAction.PAYMENT_PAY, AuditTargetType.PAYMENT, id, detail);
    eventPublisher.publish(
        new PaymentPaidEvent(
            id,
            payment.getInvoiceId(),
            payment.getCompanyId(),
            payment.getCurrency(),
            payment.getAmount(),
            java.time.LocalDate.now()));
    log.info("付款登记 {}：发票 {}，金额 {}", payment.getDocNo(), payment.getInvoiceId(), payment.getAmount());
    return presenter.payment(load(id));
  }

  public PaymentRequest load(long id) {
    return paymentMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.PAYMENT_NOT_FOUND));
  }

  private BizException conflictOrStatus(long id, int version) {
    PaymentRequest current = load(id);
    if (current.getVersion() != version) {
      return new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    return new BizException(ErrorCode.PAYMENT_STATUS_INVALID);
  }
}
