package com.spe.context.sales.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.CustomerBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 销售订单（docs/erp/35 §4）：确认时校验客户信用，信用占用留给开票。 */
@Service
@RequiredArgsConstructor
public class SalesOrderService {

  private final SalesOrderMapper orderMapper;
  private final SalesOrderLineMapper lineMapper;
  private final SalesLineBuilder lineBuilder;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SalesPresenter presenter;
  private final AuditFacade auditFacade;

  @Transactional
  public SalesOrderView create(
      long operatorId,
      long companyId,
      long customerId,
      String currency,
      String paymentTerms,
      List<SalesLineCommand> commands) {
    CompanyBrief company =
        masterDataQueryFacade
            .findCompany(companyId)
            .filter(CompanyBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    CustomerBrief customer = requireCustomer(customerId);
    String normalizedCurrency = SalesSupport.resolveCurrency(company, customer, currency);
    SalesLineBuilder.Result built = lineBuilder.build(commands);
    SalesOrder order = new SalesOrder();
    order.setOrderNo(SalesSupport.docNo("SO"));
    order.setCompanyId(companyId);
    order.setCustomerId(customerId);
    order.setCurrency(normalizedCurrency);
    order.setPaymentTerms(
        paymentTerms == null || paymentTerms.isBlank() ? customer.paymentTerms() : paymentTerms);
    order.setStatus(SalesOrderStatus.DRAFT);
    order.setNetAmount(built.netAmount());
    order.setTaxAmount(built.taxAmount());
    order.setTotalAmount(built.totalAmount());
    order.setVersion(0);
    order.setCreatedBy(operatorId);
    orderMapper.insert(order);
    insertLines(order.getId(), built.lines());
    auditFacade.record(
        AuditAction.SALES_ORDER_CREATE,
        AuditTargetType.SALES_ORDER,
        order.getId(),
        Map.of(
            "orderNo", order.getOrderNo(),
            "customerId", customerId,
            "lineCount", built.lines().size(),
            "amount", order.getTotalAmount()));
    return presenter.order(orderMapper.findById(order.getId()).orElseThrow());
  }

  /** 报价接受 → DRAFT 销售订单（报价本身仍由 QuoteService 置 ACCEPTED）。 */
  @Transactional
  public SalesOrderView createFromQuote(
      long operatorId, SalesQuote quote, List<SalesQuoteLine> quoteLines) {
    CustomerBrief customer = requireCustomer(quote.getCustomerId());
    List<SalesLineCommand> commands =
        quoteLines.stream()
            .map(
                line ->
                    new SalesLineCommand(
                        line.getItemId(),
                        line.getQty(),
                        line.getUom(),
                        line.getUnitPrice(),
                        line.getDiscountRate(),
                        line.getTaxRate()))
            .toList();
    SalesLineBuilder.Result built = lineBuilder.build(commands);
    SalesOrder order = new SalesOrder();
    order.setOrderNo(SalesSupport.docNo("SO"));
    order.setCompanyId(quote.getCompanyId());
    order.setCustomerId(quote.getCustomerId());
    order.setSourceQuoteId(quote.getId());
    order.setCurrency(quote.getCurrency());
    order.setPaymentTerms(customer.paymentTerms());
    order.setStatus(SalesOrderStatus.DRAFT);
    order.setNetAmount(built.netAmount());
    order.setTaxAmount(built.taxAmount());
    order.setTotalAmount(built.totalAmount());
    order.setVersion(0);
    order.setCreatedBy(operatorId);
    orderMapper.insert(order);
    insertLines(order.getId(), built.lines());
    auditFacade.record(
        AuditAction.SALES_ORDER_CREATE,
        AuditTargetType.SALES_ORDER,
        order.getId(),
        Map.of(
            "orderNo", order.getOrderNo(),
            "quoteId", quote.getId(),
            "lineCount", built.lines().size()));
    return presenter.order(orderMapper.findById(order.getId()).orElseThrow());
  }

  @Transactional
  public SalesOrderView confirm(long id, long operatorId) {
    SalesOrder order = lock(id);
    if (order.getStatus() != SalesOrderStatus.DRAFT) {
      throw new BizException(ErrorCode.SALES_ORDER_STATUS_INVALID);
    }
    CustomerBrief customer = requireCustomer(order.getCustomerId());
    if (customer.availableCredit().compareTo(order.getTotalAmount()) < 0) {
      throw new BizException(
          ErrorCode.SALES_CREDIT_EXCEEDED,
          "可用信用 " + customer.availableCredit() + "，订单金额 " + order.getTotalAmount());
    }
    if (orderMapper.markConfirmed(id, operatorId, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.SALES_ORDER_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.SALES_ORDER_CONFIRM,
        AuditTargetType.SALES_ORDER,
        id,
        Map.of("orderNo", order.getOrderNo(), "amount", order.getTotalAmount()));
    return presenter.order(orderMapper.findById(id).orElseThrow());
  }

  @Transactional
  public SalesOrderView cancel(long id, long operatorId) {
    SalesOrder order = lock(id);
    if (orderMapper.markCanceled(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.SALES_ORDER_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.SALES_ORDER_CANCEL,
        AuditTargetType.SALES_ORDER,
        id,
        Map.of("orderNo", order.getOrderNo()));
    return presenter.order(orderMapper.findById(id).orElseThrow());
  }

  @Transactional(readOnly = true)
  public PageResult<SalesOrder> page(
      int page, int size, Long companyId, Long customerId, String status) {
    PageQuery query = PageQuery.of(page, size);
    SalesOrderStatus parsed =
        status == null || status.isBlank()
            ? null
            : parseStatus(status);
    return PageResult.of(
        orderMapper.findPage(companyId, customerId, parsed, query.offset(), query.size()),
        orderMapper.countBy(companyId, customerId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public SalesOrderView detail(long id) {
    return presenter.order(load(id));
  }

  private void insertLines(long orderId, List<SalesSupport.CalculatedLine> lines) {
    int lineNo = 1;
    for (SalesSupport.CalculatedLine line : lines) {
      SalesOrderLine entity = new SalesOrderLine();
      entity.setOrderId(orderId);
      entity.setLineNo(lineNo++);
      entity.setItemId(line.itemId());
      entity.setQty(line.qty());
      entity.setUom(line.uom());
      entity.setUnitPrice(line.unitPrice());
      entity.setDiscountRate(line.discountRate());
      entity.setTaxRate(line.taxRate());
      entity.setNetAmount(line.netAmount());
      entity.setTaxAmount(line.taxAmount());
      entity.setShippedQty(BigDecimal.ZERO);
      entity.setInvoicedQty(BigDecimal.ZERO);
      lineMapper.insert(entity);
    }
  }

  private CustomerBrief requireCustomer(long customerId) {
    return masterDataQueryFacade
        .findCustomer(customerId)
        .filter(CustomerBrief::active)
        .orElseThrow(() -> new BizException(ErrorCode.CUSTOMER_NOT_FOUND));
  }

  private SalesOrder load(long id) {
    return orderMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.SALES_ORDER_NOT_FOUND));
  }

  private SalesOrder lock(long id) {
    return orderMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.SALES_ORDER_NOT_FOUND));
  }

  private static SalesOrderStatus parseStatus(String status) {
    try {
      return SalesOrderStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "销售订单状态不合法：" + status);
    }
  }
}
