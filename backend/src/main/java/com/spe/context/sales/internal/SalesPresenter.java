package com.spe.context.sales.internal;

import com.spe.context.masterdata.api.CustomerBrief;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 销售视图装配：客户/物料名称批量取。 */
@Component
@RequiredArgsConstructor
public class SalesPresenter {

  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SalesQuoteLineMapper quoteLineMapper;
  private final SalesOrderLineMapper orderLineMapper;

  @Transactional(readOnly = true)
  public SalesQuoteView quote(SalesQuote quote) {
    CustomerBrief customer = masterDataQueryFacade.findCustomer(quote.getCustomerId()).orElse(null);
    List<SalesQuoteLine> lines = quoteLineMapper.findByQuoteId(quote.getId());
    Map<Long, ItemBrief> items = itemsOf(lines.stream().map(SalesQuoteLine::getItemId).toList());
    return new SalesQuoteView(
        quote.getId(),
        quote.getQuoteNo(),
        quote.getCompanyId(),
        quote.getCustomerId(),
        customer == null ? null : customer.code(),
        customer == null ? null : customer.name(),
        quote.getCurrency(),
        quote.getValidUntil(),
        quote.getStatus(),
        quote.getNetAmount(),
        quote.getTaxAmount(),
        quote.getTotalAmount(),
        quote.getVersion(),
        quote.getCreatedAt(),
        quote.getSentAt(),
        quote.getAcceptedAt(),
        quote.getConvertedOrderId(),
        lines.stream()
            .map(
                line -> {
                  ItemBrief item = items.get(line.getItemId());
                  return new SalesQuoteLineView(
                      line.getId(),
                      line.getLineNo(),
                      line.getItemId(),
                      item == null ? null : item.code(),
                      item == null ? null : item.name(),
                      line.getQty(),
                      line.getUom(),
                      line.getUnitPrice(),
                      line.getDiscountRate(),
                      line.getTaxRate(),
                      line.getNetAmount(),
                      line.getTaxAmount());
                })
            .toList());
  }

  @Transactional(readOnly = true)
  public SalesOrderView order(SalesOrder order) {
    CustomerBrief customer = masterDataQueryFacade.findCustomer(order.getCustomerId()).orElse(null);
    List<SalesOrderLine> lines = orderLineMapper.findByOrderId(order.getId());
    Map<Long, ItemBrief> items = itemsOf(lines.stream().map(SalesOrderLine::getItemId).toList());
    return new SalesOrderView(
        order.getId(),
        order.getOrderNo(),
        order.getCompanyId(),
        order.getCustomerId(),
        customer == null ? null : customer.code(),
        customer == null ? null : customer.name(),
        order.getSourceQuoteId(),
        order.getCurrency(),
        order.getPaymentTerms(),
        order.getStatus(),
        order.getNetAmount(),
        order.getTaxAmount(),
        order.getTotalAmount(),
        order.getVersion(),
        order.getCreatedAt(),
        order.getConfirmedAt(),
        lines.stream()
            .map(
                line -> {
                  ItemBrief item = items.get(line.getItemId());
                  return new SalesOrderLineView(
                      line.getId(),
                      line.getLineNo(),
                      line.getItemId(),
                      item == null ? null : item.code(),
                      item == null ? null : item.name(),
                      line.getQty(),
                      line.getUom(),
                      line.getUnitPrice(),
                      line.getDiscountRate(),
                      line.getTaxRate(),
                      line.getNetAmount(),
                      line.getTaxAmount(),
                      line.getShippedQty(),
                      line.getInvoicedQty());
                })
            .toList());
  }

  private Map<Long, ItemBrief> itemsOf(List<Long> itemIds) {
    return masterDataQueryFacade.findItems(itemIds).stream()
        .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
  }
}
