package com.spe.context.procurement.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 三单匹配（docs/erp/50 §3）：PO 价格/数量 ↔ GR 实收 ↔ 发票。
 *
 * <p>容差 = `max(百分比 × 基准, 绝对下限)`；超差**不抛出**，而是落 EXCEPTION，由审批环节要求原因。
 * 数量基准是「已收 - 已开票」，没有收货时任何开票数量都算超差——不能拿"2% 容差"凭空开票。
 */
@Component
@RequiredArgsConstructor
public class InvoiceMatchService {

  private final P2pToleranceMapper toleranceMapper;
  private final PurchaseOrderLineMapper purchaseOrderLineMapper;

  @Transactional(readOnly = true)
  public P2pMatchResult match(SupplierInvoice invoice, List<SupplierInvoiceLine> lines) {
    P2pTolerance priceTolerance = tolerance(MatchDimension.PRICE, invoice.getCompanyId());
    P2pTolerance qtyTolerance = tolerance(MatchDimension.QTY, invoice.getCompanyId());

    BigDecimal maxPriceVariance = BigDecimal.ZERO;
    BigDecimal maxQtyVariance = BigDecimal.ZERO;
    BigDecimal maxAmountVariance = BigDecimal.ZERO;
    List<String> reasons = new ArrayList<>();

    for (SupplierInvoiceLine line : lines) {
      PurchaseOrderLine poLine =
          purchaseOrderLineMapper
              .findByIdAndPo(line.getPoLineId(), invoice.getPoId())
              .orElseThrow(
                  () -> new BizException(ErrorCode.SUPPLIER_INVOICE_LINE_INVALID, "发票行不属于该订单"));
      BigDecimal priceVariance = line.getUnitPrice().subtract(poLine.getPrice()).abs();
      BigDecimal priceAllowed =
          poLine.getPrice().multiply(priceTolerance.getPercent()).max(priceTolerance.getAbsolute());
      if (priceVariance.compareTo(priceAllowed) > 0) {
        maxPriceVariance = maxPriceVariance.max(priceVariance);
        reasons.add(
            "行 " + line.getLineNo() + " 单价差异 " + priceVariance + "，允许 " + priceAllowed);
      }

      BigDecimal available =
          poLine
              .getReceivedQty()
              .subtract(poLine.getReturnedQty())
              .subtract(poLine.getInvoicedQty());
      if (available.signum() < 0) {
        available = BigDecimal.ZERO;
      }
      BigDecimal netReceived = poLine.getReceivedQty().subtract(poLine.getReturnedQty());
      if (netReceived.signum() < 0) {
        netReceived = BigDecimal.ZERO;
      }
      BigDecimal qtyVariance = line.getQty().subtract(available).max(BigDecimal.ZERO);
      BigDecimal qtyAllowed =
          netReceived.signum() == 0
              ? BigDecimal.ZERO
              : netReceived.multiply(qtyTolerance.getPercent()).max(qtyTolerance.getAbsolute());
      if (qtyVariance.compareTo(qtyAllowed) > 0) {
        maxQtyVariance = maxQtyVariance.max(qtyVariance);
        reasons.add("行 " + line.getLineNo() + " 数量超出可开票 " + qtyVariance);
      }

      BigDecimal amountVariance = priceVariance.multiply(line.getQty());
      maxAmountVariance = maxAmountVariance.max(amountVariance);
    }

    P2pMatchResult result = new P2pMatchResult();
    result.setInvoiceId(invoice.getId());
    result.setStatus(reasons.isEmpty() ? MatchStatus.MATCHED : MatchStatus.EXCEPTION);
    result.setPriceVariance(maxPriceVariance);
    result.setQtyVariance(maxQtyVariance);
    result.setAmountVariance(maxAmountVariance);
    result.setDetail(reasons.isEmpty() ? null : truncate(String.join("；", reasons), 1000));
    return result;
  }

  /**
   * 单行累计可开票上限 = 已收 + max(已收×数量容差, 绝对容差)。
   *
   * <p>批准 MATCHED 发票时用它做行级守卫；没有收货时为 0——不能靠容差凭空开票。
   */
  @Transactional(readOnly = true)
  public BigDecimal maxInvoiceableQty(PurchaseOrderLine poLine, long companyId) {
    BigDecimal netReceived = poLine.getReceivedQty().subtract(poLine.getReturnedQty());
    if (netReceived.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    P2pTolerance tolerance = tolerance(MatchDimension.QTY, companyId);
    return netReceived.add(
        netReceived.multiply(tolerance.getPercent()).max(tolerance.getAbsolute()));
  }

  private P2pTolerance tolerance(MatchDimension dimension, long companyId) {
    return toleranceMapper
        .findApplicable(dimension, companyId)
        .orElseThrow(() -> new BizException(ErrorCode.MATCH_TOLERANCE_NOT_CONFIGURED));
  }

  private static String truncate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }
}
