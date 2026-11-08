package com.spe.context.finance.internal;

import com.spe.context.sales.api.events.CustomerReceiptPostedEvent;
import com.spe.context.sales.api.events.SalesInvoicedEvent;
import com.spe.context.sales.api.events.SalesReturnCreditedEvent;
import com.spe.context.sales.api.events.SalesShipmentPostedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 销售事件 → 财务过账（同一事务；映射缺失回滚开票/收款）。 */
@Component
@RequiredArgsConstructor
public class SalesPostingListener {

  private final JournalPostingService postingService;

  @EventListener
  public void onSalesShipmentPosted(SalesShipmentPostedEvent event) {
    postingService.postSalesShipment(event);
  }

  @EventListener
  public void onSalesInvoiced(SalesInvoicedEvent event) {
    postingService.postSalesInvoice(event);
  }

  @EventListener
  public void onCustomerReceiptPosted(CustomerReceiptPostedEvent event) {
    postingService.postCustomerReceipt(event);
  }

  @EventListener
  public void onSalesReturnCredited(SalesReturnCreditedEvent event) {
    postingService.postSalesReturnCredit(event);
    postingService.postSalesReturnCost(event);
  }
}
