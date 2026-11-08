package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.ApSubledgerSummary;
import com.spe.context.procurement.api.ProcurementApQueryFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link ProcurementApQueryFacade} 的实现：只读汇总，实体不出上下文。 */
@Component
@RequiredArgsConstructor
public class ProcurementApQueryFacadeImpl implements ProcurementApQueryFacade {

  private final SupplierInvoiceMapper invoiceMapper;
  private final PurchaseReturnMapper returnMapper;

  @Override
  @Transactional(readOnly = true)
  public ApSubledgerSummary summarise(long companyId, java.time.LocalDate asOf) {
    java.math.BigDecimal invoiced = invoiceMapper.sumApprovedTotalAsOf(companyId, asOf);
    java.math.BigDecimal paid = invoiceMapper.sumPaidAsOf(companyId, asOf);
    java.math.BigDecimal returned = returnMapper.sumInvoicedAsOf(companyId, asOf);
    return new ApSubledgerSummary(
        invoiced, paid, returned, invoiced.subtract(paid).subtract(returned));
  }
}
