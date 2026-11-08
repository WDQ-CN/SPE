package com.spe.context.integration.internal;

import com.spe.context.procurement.api.events.GoodsReceivedEvent;
import com.spe.context.sales.api.events.SalesInvoicedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 关键业务事件写入 outbox（与业务同事务；派发不阻塞业务）。 */
@Component
@RequiredArgsConstructor
public class IntegrationOutboxListener {

  private final OutboxService outboxService;

  @EventListener
  public void onGoodsReceived(GoodsReceivedEvent event) {
    outboxService.append("GoodsReceived", event.goodsReceiptId(), event);
  }

  @EventListener
  public void onSalesInvoiced(SalesInvoicedEvent event) {
    outboxService.append("SalesInvoiced", event.invoiceId(), event);
  }
}
