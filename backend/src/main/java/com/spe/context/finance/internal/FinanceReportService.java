package com.spe.context.finance.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.inventory.api.InventoryStockValuation;
import com.spe.context.inventory.api.InventoryValuationFacade;
import com.spe.context.procurement.api.ApSubledgerSummary;
import com.spe.context.procurement.api.ProcurementApQueryFacade;
import com.spe.context.masterdata.api.AccountMappingBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 财务查询与报告（W5）：凭证、试算平衡、AP 子账对账。 */
@Service
@RequiredArgsConstructor
public class FinanceReportService {

  private final JournalEntryMapper entryMapper;
  private final JournalLineMapper lineMapper;
  private final FinancePresenter presenter;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final ProcurementApQueryFacade procurementApQueryFacade;
  private final InventoryValuationFacade inventoryValuationFacade;

  @Transactional(readOnly = true)
  public PageResult<JournalEntrySummaryView> page(
      int page, int size, Long companyId, String sourceType, String keyword, LocalDate from, LocalDate to) {
    PageQuery query = PageQuery.of(page, size);
    String like = FinanceSupport.keyword(keyword);
    String source = sourceType == null || sourceType.isBlank() ? null : sourceType.trim().toUpperCase();
    List<JournalEntry> rows =
        entryMapper.findPage(companyId, source, like, from, to, query.offset(), query.size());
    return PageResult.of(
        presenter.entries(rows),
        entryMapper.countBy(companyId, source, like, from, to),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public JournalEntryView detail(long id) {
    JournalEntry entry =
        entryMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.JOURNAL_ENTRY_NOT_FOUND));
    return presenter.entry(entry);
  }

  @Transactional(readOnly = true)
  public TrialBalanceView trialBalance(long companyId, LocalDate from, LocalDate to) {
    return presenter.trialBalance(
        companyId, from, to, lineMapper.totals(companyId, from, to, null));
  }

  /**
   * AP 子账 vs GL：子账 = 已批准发票 - 已付；GL = 应付科目贷方 - 借方（截至 asOf）。
   *
   * <p>调用方必须在同一公司内配置 `SUPPLIER_INVOICE` 与 `PAYMENT` 的映射，否则 2402。
   */
  @Transactional(readOnly = true)
  public ApReconciliationView apReconciliation(long companyId, LocalDate asOf) {
    LocalDate effectiveDate = asOf == null ? LocalDate.now() : asOf;
    AccountMappingBrief invoiceMapping =
        masterDataQueryFacade
            .findEffectiveAccountMapping(companyId, JournalPostingService.SOURCE_SUPPLIER_INVOICE, effectiveDate)
            .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_MAPPING_MISSING, "未配置发票科目映射"));
    AccountMappingBrief paymentMapping =
        masterDataQueryFacade
            .findEffectiveAccountMapping(companyId, JournalPostingService.SOURCE_PAYMENT, effectiveDate)
            .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_MAPPING_MISSING, "未配置付款科目映射"));
    Set<Long> apAccounts =
        new java.util.LinkedHashSet<>(
            List.of(invoiceMapping.creditAccountId(), paymentMapping.debitAccountId()));
    List<JournalLineMapper.AccountTotals> totals =
        lineMapper.totals(companyId, null, effectiveDate, apAccounts);
    BigDecimal glBalance =
        totals.stream()
            .map(total -> total.creditTotal().subtract(total.debitTotal()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    ApSubledgerSummary subledger = procurementApQueryFacade.summarise(companyId, effectiveDate);
    BigDecimal difference = subledger.balance().subtract(glBalance);
    return new ApReconciliationView(
        companyId,
        effectiveDate,
        subledger.invoicedTotal(),
        subledger.paidTotal(),
        subledger.returnedTotal(),
        subledger.balance(),
        glBalance,
        difference,
        difference.signum() == 0);
  }

  /**
   * 库存子账 vs GL（docs/erp/54 §4.8）。子账取库存估值门面；GL 取库存类科目截至 asOf 的
   * 借方 - 贷方。差异不抛异常：可能是未过账的盘点调整，报表如实呈现。
   */
  @Transactional(readOnly = true)
  public InventoryReconciliationView inventoryReconciliation(long companyId, LocalDate asOf) {
    LocalDate effectiveDate = asOf == null ? LocalDate.now() : asOf;
    List<InventoryStockValuation> valuations = inventoryValuationFacade.valuations(companyId);
    BigDecimal subledgerValue =
        valuations.stream()
            .map(InventoryStockValuation::value)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    Set<Long> inventoryAccounts = new LinkedHashSet<>();
    addAccount(inventoryAccounts, companyId, "GOODS_RECEIPT", effectiveDate, true);
    addAccount(inventoryAccounts, companyId, "PURCHASE_RETURN", effectiveDate, false);
    addAccount(inventoryAccounts, companyId, "INVENTORY_ISSUE", effectiveDate, false);
    addAccount(inventoryAccounts, companyId, "INVENTORY_ADJUSTMENT_GAIN", effectiveDate, true);
    addAccount(inventoryAccounts, companyId, "INVENTORY_ADJUSTMENT_LOSS", effectiveDate, false);
    // 销售发货贷记库存、销售退货成本借记库存；漏掉这两类会让 GL 与子账长期假差异。
    addAccount(inventoryAccounts, companyId, "SALES_SHIPMENT", effectiveDate, false);
    addAccount(inventoryAccounts, companyId, "SALES_RETURN_COST", effectiveDate, true);
    BigDecimal glValue =
        inventoryAccounts.isEmpty()
            ? BigDecimal.ZERO
            : lineMapper.totals(companyId, null, effectiveDate, inventoryAccounts).stream()
                .map(total -> total.debitTotal().subtract(total.creditTotal()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal difference = subledgerValue.subtract(glValue);
    List<InventoryReconciliationRowView> rows =
        valuations.stream()
            .map(
                row ->
                    new InventoryReconciliationRowView(
                        row.warehouseId(),
                        row.itemId(),
                        row.lotId(),
                        row.valuationMethod(),
                        row.qty(),
                        row.value()))
            .toList();
    return new InventoryReconciliationView(
        companyId,
        effectiveDate,
        subledgerValue,
        glValue,
        difference,
        difference.signum() == 0,
        rows);
  }

  private void addAccount(
      Set<Long> target, long companyId, String eventType, LocalDate date, boolean inventoryIsDebit) {
    masterDataQueryFacade
        .findEffectiveAccountMapping(companyId, eventType, date)
        .ifPresent(
            mapping -> {
              Long accountId =
                  inventoryIsDebit ? mapping.debitAccountId() : mapping.creditAccountId();
              if (accountId != null && accountId > 0) {
                target.add(accountId);
              }
            });
  }
}
