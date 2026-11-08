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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 销售报价（docs/erp/35 §4）：DRAFT → SENT → ACCEPTED/EXPIRED。 */
@Service
@RequiredArgsConstructor
public class SalesQuoteService {

  private final SalesQuoteMapper quoteMapper;
  private final SalesQuoteLineMapper lineMapper;
  private final SalesLineBuilder lineBuilder;
  private final SalesOrderService orderService;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SalesPresenter presenter;
  private final AuditFacade auditFacade;

  @Transactional
  public SalesQuoteView create(
      long operatorId,
      long companyId,
      long customerId,
      String currency,
      LocalDate validUntil,
      List<SalesLineCommand> commands) {
    CompanyBrief company =
        masterDataQueryFacade
            .findCompany(companyId)
            .filter(CompanyBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.COMPANY_NOT_FOUND));
    CustomerBrief customer = requireCustomer(customerId);
    if (validUntil == null || validUntil.isBefore(LocalDate.now(ZoneOffset.UTC))) {
      throw new BizException(ErrorCode.BAD_REQUEST, "报价有效期不能早于今天");
    }
    String normalizedCurrency = SalesSupport.resolveCurrency(company, customer, currency);
    SalesLineBuilder.Result built = lineBuilder.build(commands);
    SalesQuote quote = new SalesQuote();
    quote.setQuoteNo(SalesSupport.docNo("SQ"));
    quote.setCompanyId(companyId);
    quote.setCustomerId(customerId);
    quote.setCurrency(normalizedCurrency);
    quote.setValidUntil(validUntil);
    quote.setStatus(SalesQuoteStatus.DRAFT);
    quote.setNetAmount(built.netAmount());
    quote.setTaxAmount(built.taxAmount());
    quote.setTotalAmount(built.totalAmount());
    quote.setVersion(0);
    quote.setCreatedBy(operatorId);
    quoteMapper.insert(quote);
    int lineNo = 1;
    for (SalesSupport.CalculatedLine line : built.lines()) {
      SalesQuoteLine entity = new SalesQuoteLine();
      entity.setQuoteId(quote.getId());
      entity.setLineNo(lineNo++);
      entity.setItemId(line.itemId());
      entity.setQty(line.qty());
      entity.setUom(line.uom());
      entity.setUnitPrice(line.unitPrice());
      entity.setDiscountRate(line.discountRate());
      entity.setTaxRate(line.taxRate());
      entity.setNetAmount(line.netAmount());
      entity.setTaxAmount(line.taxAmount());
      lineMapper.insert(entity);
    }
    auditFacade.record(
        AuditAction.SALES_QUOTE_CREATE,
        AuditTargetType.SALES_QUOTE,
        quote.getId(),
        Map.of(
            "quoteNo", quote.getQuoteNo(),
            "customerId", customerId,
            "lineCount", built.lines().size(),
            "amount", quote.getTotalAmount()));
    return presenter.quote(quoteMapper.findById(quote.getId()).orElseThrow());
  }

  @Transactional
  public SalesQuoteView send(long id) {
    SalesQuote quote = lock(id);
    if (quoteMapper.markSent(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.SALES_QUOTE_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.SALES_QUOTE_SEND,
        AuditTargetType.SALES_QUOTE,
        id,
        Map.of("quoteNo", quote.getQuoteNo()));
    return presenter.quote(quoteMapper.findById(id).orElseThrow());
  }

  @Transactional
  public SalesOrderView accept(long id, long operatorId) {
    SalesQuote quote = lock(id);
    if (quote.getStatus() != SalesQuoteStatus.SENT) {
      throw new BizException(ErrorCode.SALES_QUOTE_STATUS_INVALID);
    }
    if (quote.getValidUntil().isBefore(LocalDate.now(ZoneOffset.UTC))) {
      quoteMapper.markExpired(id);
      throw new BizException(ErrorCode.SALES_QUOTE_STATUS_INVALID, "报价已过期");
    }
    List<SalesQuoteLine> lines = lineMapper.findByQuoteId(id);
    if (lines.isEmpty()) {
      throw new BizException(ErrorCode.SALES_QUOTE_STATUS_INVALID, "报价没有行");
    }
    SalesOrderView order = orderService.createFromQuote(operatorId, quote, lines);
    if (quoteMapper.markAccepted(id, order.id(), LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.SALES_QUOTE_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.SALES_QUOTE_ACCEPT,
        AuditTargetType.SALES_QUOTE,
        id,
        Map.of(
            "quoteNo", quote.getQuoteNo(),
            "orderId", order.id(),
            "orderNo", order.orderNo()));
    return order;
  }

  @Transactional(readOnly = true)
  public PageResult<SalesQuote> page(
      int page, int size, Long companyId, Long customerId, String status) {
    PageQuery query = PageQuery.of(page, size);
    SalesQuoteStatus parsed =
        status == null || status.isBlank()
            ? null
            : parseStatus(status);
    return PageResult.of(
        quoteMapper.findPage(companyId, customerId, parsed, query.offset(), query.size()),
        quoteMapper.countBy(companyId, customerId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public SalesQuoteView detail(long id) {
    return presenter.quote(load(id));
  }

  private CustomerBrief requireCustomer(long customerId) {
    return masterDataQueryFacade
        .findCustomer(customerId)
        .filter(CustomerBrief::active)
        .orElseThrow(() -> new BizException(ErrorCode.CUSTOMER_NOT_FOUND));
  }

  private SalesQuote load(long id) {
    return quoteMapper
        .findById(id)
        .orElseThrow(() -> new BizException(ErrorCode.SALES_QUOTE_NOT_FOUND));
  }

  private SalesQuote lock(long id) {
    return quoteMapper
        .lockById(id)
        .orElseThrow(() -> new BizException(ErrorCode.SALES_QUOTE_NOT_FOUND));
  }

  private static SalesQuoteStatus parseStatus(String status) {
    try {
      return SalesQuoteStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "报价状态不合法：" + status);
    }
  }
}
