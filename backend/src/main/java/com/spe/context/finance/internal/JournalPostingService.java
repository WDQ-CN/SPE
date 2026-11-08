package com.spe.context.finance.internal;

import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.asset.api.events.AssetCapitalizedEvent;
import com.spe.context.asset.api.events.AssetDepreciatedEvent;
import com.spe.context.masterdata.api.AccountMappingBrief;
import com.spe.context.masterdata.api.GlAccountBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.procurement.api.events.GoodsReceiptReversedEvent;
import com.spe.context.procurement.api.events.GoodsReceivedEvent;
import com.spe.context.procurement.api.events.GoodsReceivedLine;
import com.spe.context.procurement.api.events.PaymentPaidEvent;
import com.spe.context.procurement.api.events.PurchaseReturnPostedEvent;
import com.spe.context.procurement.api.events.SupplierInvoiceApprovedEvent;
import com.spe.context.sales.api.events.CustomerReceiptPostedEvent;
import com.spe.context.sales.api.events.SalesInvoicedEvent;
import com.spe.context.sales.api.events.SalesReturnCreditedEvent;
import com.spe.context.sales.api.events.SalesShipmentPostedEvent;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 最小过账引擎（W5；docs/erp/34）。
 *
 * <p>来源幂等：`(source_type, source_id)` 唯一键；重复事件直接跳过，不重复入账。每张凭证先校验
 * 借贷相等与合计一致（2401），再校验科目存在、启用且属于本公司（2405），最后写入并留审计。
 *
 * <p>E1 的取舍：过账与业务在同一事务内（失败回滚业务），不引入独立的 outbox/posting_exception；
 * 这保证"不会静默丢失"，代价是财务配置错误会阻断收货/发票/付款——E2 接入 outbox 后再解耦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JournalPostingService {

  public static final String SOURCE_GOODS_RECEIPT = "GOODS_RECEIPT";
  public static final String SOURCE_GOODS_RECEIPT_REVERSAL = "GOODS_RECEIPT_REVERSAL";
  public static final String SOURCE_SUPPLIER_INVOICE = "SUPPLIER_INVOICE";
  public static final String SOURCE_PAYMENT = "PAYMENT";
  public static final String SOURCE_PURCHASE_RETURN = "PURCHASE_RETURN";
  public static final String MAPPING_PURCHASE_RETURN_INVOICED = "PURCHASE_RETURN_INVOICED";
  public static final String MAPPING_PURCHASE_RETURN_UNINVOICED = "PURCHASE_RETURN_UNINVOICED";
  public static final String SOURCE_SALES_INVOICE = "SALES_INVOICE";
  public static final String SOURCE_CUSTOMER_RECEIPT = "CUSTOMER_RECEIPT";
  public static final String SOURCE_SALES_SHIPMENT = "SALES_SHIPMENT";
  public static final String SOURCE_SALES_RETURN = "SALES_RETURN";
  public static final String SOURCE_SALES_RETURN_COST = "SALES_RETURN_COST";
  public static final String SOURCE_ASSET_CAPITALIZATION = "ASSET_CAPITALIZATION";
  public static final String SOURCE_ASSET_DEPRECIATION = "ASSET_DEPRECIATION";

  private final JournalEntryMapper entryMapper;
  private final JournalLineMapper lineMapper;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final AuditFacade auditFacade;

  @Transactional
  public void postGoodsReceipt(GoodsReceivedEvent event) {
    AccountMappingBrief mapping = requireMapping(event.companyId(), SOURCE_GOODS_RECEIPT, event.businessDate());
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), event.netAmount(), "采购收货（GR/IR）");
    addCredit(lines, mapping.creditAccountId(), event.netAmount(), "采购收货暂估");
    post(
        SOURCE_GOODS_RECEIPT,
        event.goodsReceiptId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        event.netAmount(),
        "采购收货 GR#" + event.goodsReceiptId(),
        lines);
  }

  /** 收货冲销：反向冲回原 GR/IR 分录，源键独立于原收货，保证一次冲销只入一次账。 */
  @Transactional
  public void postGoodsReceiptReversal(GoodsReceiptReversedEvent event) {
    BigDecimal total =
        event.lines().stream()
            .map(GoodsReceivedLine::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    AccountMappingBrief mapping =
        requireMapping(event.companyId(), SOURCE_GOODS_RECEIPT, event.businessDate());
    List<DraftLine> lines = new ArrayList<>();
    addCredit(lines, mapping.debitAccountId(), total, "冲销采购收货（GR/IR）");
    addDebit(lines, mapping.creditAccountId(), total, "冲销采购收货暂估");
    post(
        SOURCE_GOODS_RECEIPT_REVERSAL,
        event.goodsReceiptId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        total,
        "冲销采购收货 GR#" + event.goodsReceiptId(),
        lines);
  }

  @Transactional
  public void postSupplierInvoice(SupplierInvoiceApprovedEvent event) {
    AccountMappingBrief mapping =
        requireMapping(event.companyId(), SOURCE_SUPPLIER_INVOICE, event.businessDate());
    BigDecimal net = FinanceSupport.money(event.netAmount());
    BigDecimal tax = FinanceSupport.money(event.taxAmount());
    BigDecimal total = FinanceSupport.money(event.totalAmount());
    if (net.add(tax).compareTo(total) != 0) {
      throw new BizException(ErrorCode.JOURNAL_ENTRY_UNBALANCED, "发票净额 + 税额不等于价税合计");
    }
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), net, "冲销 GR/IR");
    if (tax.signum() > 0) {
      if (mapping.taxAccountId() == null) {
        throw new BizException(ErrorCode.ACCOUNT_MAPPING_MISSING, "发票含税但映射未配置税科目");
      }
      addDebit(lines, mapping.taxAccountId(), tax, "进项税额");
    }
    addCredit(lines, mapping.creditAccountId(), total, "应付账款");
    post(
        SOURCE_SUPPLIER_INVOICE,
        event.invoiceId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        total,
        "供应商发票 IR#" + event.invoiceId(),
        lines);
  }

  @Transactional
  public void postPurchaseReturn(PurchaseReturnPostedEvent event) {
    String mappingEvent =
        event.invoiced()
            ? MAPPING_PURCHASE_RETURN_INVOICED
            : MAPPING_PURCHASE_RETURN_UNINVOICED;
    AccountMappingBrief mapping =
        requireMapping(event.companyId(), mappingEvent, event.businessDate());
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), event.netAmount(), "采购退货：冲回应付/暂估");
    addCredit(lines, mapping.creditAccountId(), event.netAmount(), "采购退货：库存出库");
    post(
        SOURCE_PURCHASE_RETURN,
        event.purchaseReturnId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        event.netAmount(),
        "采购退货 PRT#" + event.purchaseReturnId(),
        lines);
  }

  @Transactional
  public void postPayment(PaymentPaidEvent event) {
    AccountMappingBrief mapping = requireMapping(event.companyId(), SOURCE_PAYMENT, event.businessDate());
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), event.amount(), "支付应付账款");
    addCredit(lines, mapping.creditAccountId(), event.amount(), "银行存款");
    post(
        SOURCE_PAYMENT,
        event.paymentId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        event.amount(),
        "付款 PAY#" + event.paymentId(),
        lines);
  }

  /** 销售发票：借 AR（含税），贷收入（未税），贷销项税。 */
  @Transactional
  public void postSalesInvoice(SalesInvoicedEvent event) {
    AccountMappingBrief mapping =
        requireMapping(event.companyId(), SOURCE_SALES_INVOICE, event.businessDate());
    BigDecimal net = FinanceSupport.money(event.netAmount());
    BigDecimal tax = FinanceSupport.money(event.taxAmount());
    BigDecimal total = FinanceSupport.money(event.totalAmount());
    if (net.add(tax).compareTo(total) != 0) {
      throw new BizException(ErrorCode.JOURNAL_ENTRY_UNBALANCED, "销售发票净额 + 税额不等于价税合计");
    }
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), total, "应收账款");
    addCredit(lines, mapping.creditAccountId(), net, "销售收入");
    if (tax.signum() > 0) {
      if (mapping.taxAccountId() == null) {
        throw new BizException(ErrorCode.ACCOUNT_MAPPING_MISSING, "销售发票含税但映射未配置税科目");
      }
      addCredit(lines, mapping.taxAccountId(), tax, "销项税额");
    }
    post(
        SOURCE_SALES_INVOICE,
        event.invoiceId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        total,
        "销售发票 SI#" + event.invoiceId(),
        lines);
  }

  /** 资产资本化：借固定资产，贷来源（银行/AP/在建工程）。 */
  @Transactional
  public void postAssetCapitalization(AssetCapitalizedEvent event) {
    AccountMappingBrief mapping =
        requireMapping(event.companyId(), SOURCE_ASSET_CAPITALIZATION, event.businessDate());
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), event.cost(), "固定资产");
    addCredit(lines, mapping.creditAccountId(), event.cost(), "资产来源");
    post(
        SOURCE_ASSET_CAPITALIZATION,
        event.assetId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        event.cost(),
        "资产资本化 FA#" + event.assetId(),
        lines);
  }

  /** 资产折旧：借折旧费用，贷累计折旧。 */
  @Transactional
  public void postAssetDepreciation(AssetDepreciatedEvent event) {
    AccountMappingBrief mapping =
        requireMapping(event.companyId(), SOURCE_ASSET_DEPRECIATION, event.businessDate());
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), event.amount(), "折旧费用");
    addCredit(lines, mapping.creditAccountId(), event.amount(), "累计折旧");
    post(
        SOURCE_ASSET_DEPRECIATION,
        event.depreciationId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        event.amount(),
        "资产折旧 " + event.period() + " FA#" + event.assetId(),
        lines);
  }

  /** 销售发货成本：借 COGS，贷库存。 */
  @Transactional
  public void postSalesShipment(SalesShipmentPostedEvent event) {
    AccountMappingBrief mapping =
        requireMapping(event.companyId(), SOURCE_SALES_SHIPMENT, event.businessDate());
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), event.totalCost(), "主营业务成本");
    addCredit(lines, mapping.creditAccountId(), event.totalCost(), "库存商品");
    post(
        SOURCE_SALES_SHIPMENT,
        event.shipmentId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        event.totalCost(),
        "销售发货 SHP#" + event.shipmentId(),
        lines);
  }

  /** 销售退货贷项：借收入/销项税，贷 AR。 */
  @Transactional
  public void postSalesReturnCredit(SalesReturnCreditedEvent event) {
    AccountMappingBrief mapping =
        requireMapping(event.companyId(), SOURCE_SALES_RETURN, event.businessDate());
    BigDecimal net = FinanceSupport.money(event.netAmount());
    BigDecimal tax = FinanceSupport.money(event.taxAmount());
    BigDecimal total = FinanceSupport.money(event.totalAmount());
    if (net.add(tax).compareTo(total) != 0) {
      throw new BizException(ErrorCode.JOURNAL_ENTRY_UNBALANCED, "退货净额 + 税额不等于价税合计");
    }
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), net, "冲减销售收入");
    if (tax.signum() > 0) {
      if (mapping.taxAccountId() == null) {
        throw new BizException(ErrorCode.ACCOUNT_MAPPING_MISSING, "销售退货含税但映射未配置税科目");
      }
      addDebit(lines, mapping.taxAccountId(), tax, "冲减销项税额");
    }
    addCredit(lines, mapping.creditAccountId(), total, "冲减应收账款");
    post(
        SOURCE_SALES_RETURN,
        event.returnId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        total,
        "销售退货 SRT#" + event.returnId(),
        lines);
  }

  /** 销售退货成本：借库存，贷 COGS。 */
  @Transactional
  public void postSalesReturnCost(SalesReturnCreditedEvent event) {
    if (event.costTotal() == null || event.costTotal().signum() <= 0) {
      return;
    }
    AccountMappingBrief mapping =
        requireMapping(event.companyId(), SOURCE_SALES_RETURN_COST, event.businessDate());
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), event.costTotal(), "退货入库");
    addCredit(lines, mapping.creditAccountId(), event.costTotal(), "冲减主营业务成本");
    post(
        SOURCE_SALES_RETURN_COST,
        event.returnId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        event.costTotal(),
        "销售退货成本 SRT#" + event.returnId(),
        lines);
  }

  /** 客户收款：借银行，贷 AR。 */
  @Transactional
  public void postCustomerReceipt(CustomerReceiptPostedEvent event) {
    AccountMappingBrief mapping =
        requireMapping(event.companyId(), SOURCE_CUSTOMER_RECEIPT, event.businessDate());
    List<DraftLine> lines = new ArrayList<>();
    addDebit(lines, mapping.debitAccountId(), event.amount(), "银行存款");
    addCredit(lines, mapping.creditAccountId(), event.amount(), "核销应收账款");
    post(
        SOURCE_CUSTOMER_RECEIPT,
        event.receiptId(),
        event.companyId(),
        event.businessDate(),
        event.currency(),
        event.amount(),
        "客户收款 RCP#" + event.receiptId(),
        lines);
  }

  private void post(
      String sourceType,
      long sourceId,
      long companyId,
      LocalDate entryDate,
      String currency,
      BigDecimal total,
      String description,
      List<DraftLine> drafts) {
    BigDecimal normalizedTotal = FinanceSupport.money(total);
    if (normalizedTotal.signum() <= 0) {
      log.info("来源 {}#{} 金额为 0，跳过过账", sourceType, sourceId);
      return;
    }
    if (entryMapper.findBySource(sourceType, sourceId).isPresent()) {
      log.info("来源 {}#{} 已过账，重复事件跳过", sourceType, sourceId);
      return;
    }
    if (drafts.isEmpty()) {
      throw new BizException(ErrorCode.JOURNAL_ENTRY_UNBALANCED, "凭证没有分录行");
    }
    BigDecimal debit = drafts.stream().map(DraftLine::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal credit = drafts.stream().map(DraftLine::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (debit.compareTo(credit) != 0 || debit.compareTo(normalizedTotal) != 0) {
      throw new BizException(
          ErrorCode.JOURNAL_ENTRY_UNBALANCED,
          "借方 " + debit + " / 贷方 " + credit + " / 合计 " + normalizedTotal);
    }
    validateAccounts(drafts, companyId);

    JournalEntry entry = new JournalEntry();
    entry.setDocNo(FinanceSupport.docNo());
    entry.setCompanyId(companyId);
    entry.setEntryDate(entryDate == null ? LocalDate.now() : entryDate);
    entry.setSourceType(sourceType);
    entry.setSourceId(sourceId);
    entry.setCurrency(currency);
    entry.setDescription(description);
    entry.setTotalAmount(normalizedTotal);
    entry.setStatus("POSTED");
    entry.setCreatedBy(currentOperatorId());
    entry.setVersion(0);
    try {
      entryMapper.insert(entry);
    } catch (DuplicateKeyException ex) {
      // 只有「来源唯一键」冲突才是幂等重复；凭证号撞库等其它唯一键冲突必须暴露，不能静默少一张凭证。
      if (entryMapper.findBySource(sourceType, sourceId).isPresent()) {
        log.info("来源 {}#{} 并发重复过账，已由唯一键拦截", sourceType, sourceId);
        return;
      }
      throw ex;
    }
    int lineNo = 1;
    for (DraftLine draft : drafts) {
      JournalLine line = new JournalLine();
      line.setEntryId(entry.getId());
      line.setLineNo(lineNo++);
      line.setAccountId(draft.accountId());
      line.setDebit(draft.debit());
      line.setCredit(draft.credit());
      line.setDescription(draft.description());
      lineMapper.insert(line);
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("sourceType", sourceType);
    detail.put("sourceId", sourceId);
    detail.put("lineCount", drafts.size());
    detail.put("total", normalizedTotal);
    detail.put("currency", currency);
    auditFacade.record(
        AuditAction.JOURNAL_ENTRY_POST, AuditTargetType.JOURNAL_ENTRY, entry.getId(), detail);
    log.info("过账 {}：{}#{}，金额 {}", entry.getDocNo(), sourceType, sourceId, normalizedTotal);
  }

  private void validateAccounts(List<DraftLine> drafts, long companyId) {
    List<Long> ids = drafts.stream().map(DraftLine::accountId).distinct().toList();
    Map<Long, GlAccountBrief> accounts =
        masterDataQueryFacade.findAccounts(ids).stream()
            .collect(Collectors.toMap(GlAccountBrief::id, Function.identity()));
    for (Long id : ids) {
      GlAccountBrief account = accounts.get(id);
      if (account == null || !account.active() || account.companyId() != companyId) {
        throw new BizException(ErrorCode.FINANCE_ACCOUNT_INVALID, "科目不可用：" + id);
      }
    }
  }

  private AccountMappingBrief requireMapping(long companyId, String eventType, LocalDate date) {
    return masterDataQueryFacade
        .findEffectiveAccountMapping(companyId, eventType, date)
        .orElseThrow(
            () ->
                new BizException(
                    ErrorCode.ACCOUNT_MAPPING_MISSING, "未配置 " + eventType + " 的科目映射"));
  }

  private static void addDebit(
      List<DraftLine> lines, long accountId, BigDecimal amount, String description) {
    BigDecimal money = FinanceSupport.money(amount);
    if (money.signum() > 0) {
      lines.add(new DraftLine(accountId, money, BigDecimal.ZERO, description));
    }
  }

  private static void addCredit(
      List<DraftLine> lines, long accountId, BigDecimal amount, String description) {
    BigDecimal money = FinanceSupport.money(amount);
    if (money.signum() > 0) {
      lines.add(new DraftLine(accountId, BigDecimal.ZERO, money, description));
    }
  }

  private static long currentOperatorId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedOperator operator) {
      return operator.uid();
    }
    return 0L;
  }

  private record DraftLine(long accountId, BigDecimal debit, BigDecimal credit, String description) {}
}
