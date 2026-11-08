package com.spe.context.finance.internal;

import com.spe.context.procurement.api.events.GoodsReceiptReversedEvent;
import com.spe.context.procurement.api.events.GoodsReceivedEvent;
import com.spe.context.procurement.api.events.PaymentPaidEvent;
import com.spe.context.procurement.api.events.PurchaseReturnPostedEvent;
import com.spe.context.procurement.api.events.SupplierInvoiceApprovedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * P2P 业务事件 → 自动过账（W5）。
 *
 * <p>监听器与业务在同一事务（05 ADR-9）：过账失败会让收货/发票批准/付款一起回滚，不会出现
 * "业务成功、财务静默丢账"。重复事件由来源唯一键幂等。
 */
@Component
@RequiredArgsConstructor
public class ProcurementPostingListener {

  private final JournalPostingService postingService;

  @EventListener
  public void onGoodsReceived(GoodsReceivedEvent event) {
    postingService.postGoodsReceipt(event);
  }

  @EventListener
  public void onGoodsReceiptReversed(GoodsReceiptReversedEvent event) {
    postingService.postGoodsReceiptReversal(event);
  }

  @EventListener
  public void onSupplierInvoiceApproved(SupplierInvoiceApprovedEvent event) {
    postingService.postSupplierInvoice(event);
  }

  @EventListener
  public void onPaymentPaid(PaymentPaidEvent event) {
    postingService.postPayment(event);
  }

  @EventListener
  public void onPurchaseReturnPosted(PurchaseReturnPostedEvent event) {
    postingService.postPurchaseReturn(event);
  }
}
