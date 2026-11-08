package com.spe.context.procurement.internal;

import com.spe.context.masterdata.api.CompanyBrief;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.UserBrief;
import com.spe.context.system.api.UserQueryFacade;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 发票/付款视图装配（W4）。 */
@Component
@RequiredArgsConstructor
public class InvoicePresenter {

  private final MasterDataQueryFacade masterDataQueryFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final UserQueryFacade userQueryFacade;
  private final PurchaseOrderMapper purchaseOrderMapper;
  private final SupplierInvoiceMapper invoiceMapper;
  private final SupplierInvoiceLineMapper invoiceLineMapper;
  private final P2pMatchResultMapper matchResultMapper;

  @Transactional(readOnly = true)
  public InvoiceView invoice(SupplierInvoice invoice) {
    return invoice(
        invoice,
        invoiceLineMapper.findByInvoiceId(invoice.getId()),
        matchResultMapper.findByInvoiceId(invoice.getId()).orElse(null));
  }

  @Transactional(readOnly = true)
  public InvoiceView invoice(
      SupplierInvoice invoice, List<SupplierInvoiceLine> lines, P2pMatchResult match) {
    CompanyBrief company = masterDataQueryFacade.findCompany(invoice.getCompanyId()).orElse(null);
    SupplierBrief supplier = supplierQueryFacade.findById(invoice.getSupplierId()).orElse(null);
    PurchaseOrder order = purchaseOrderMapper.findById(invoice.getPoId()).orElse(null);
    UserBrief creator = userQueryFacade.findById(invoice.getCreatedBy()).orElse(null);
    UserBrief approver =
        invoice.getApprovedBy() == null
            ? null
            : userQueryFacade.findById(invoice.getApprovedBy()).orElse(null);
    Map<Long, ItemBrief> items = itemsOf(lines.stream().map(SupplierInvoiceLine::getItemId).toList());
    return new InvoiceView(
        invoice.getId(),
        invoice.getDocNo(),
        invoice.getSupplierInvoiceNo(),
        invoice.getCompanyId(),
        company == null ? null : company.name(),
        invoice.getSupplierId(),
        supplier == null ? null : supplier.name(),
        invoice.getPoId(),
        order == null ? null : order.getDocNo(),
        invoice.getInvoiceDate(),
        invoice.getDueDate(),
        invoice.getCurrency(),
        invoice.getNetAmount(),
        invoice.getTaxAmount(),
        invoice.getTotalAmount(),
        invoice.getPaidAmount(),
        invoice.getStatus(),
        invoice.getCreatedBy(),
        creator == null ? null : creator.name(),
        invoice.getApprovedBy(),
        approver == null ? null : approver.name(),
        invoice.getApprovedAt(),
        invoice.getVersion(),
        invoice.getCreatedAt(),
        match == null
            ? null
            : new MatchResultView(
                match.getStatus(),
                match.getPriceVariance(),
                match.getQtyVariance(),
                match.getAmountVariance(),
                match.getDetail()),
        lines.stream().map(line -> line(line, items)).toList());
  }

  @Transactional(readOnly = true)
  public List<InvoiceSummaryView> invoices(List<SupplierInvoice> rows) {
    Map<Long, CompanyBrief> companies = companiesOf(rows.stream().map(SupplierInvoice::getCompanyId).toList());
    Map<Long, SupplierBrief> suppliers =
        supplierQueryFacade.findByIds(rows.stream().map(SupplierInvoice::getSupplierId).toList()).stream()
            .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));
    Map<Long, PurchaseOrder> orders = ordersOf(rows.stream().map(SupplierInvoice::getPoId).toList());
    return rows.stream()
        .map(
            row -> {
              CompanyBrief company = companies.get(row.getCompanyId());
              SupplierBrief supplier = suppliers.get(row.getSupplierId());
              PurchaseOrder order = orders.get(row.getPoId());
              return new InvoiceSummaryView(
                  row.getId(),
                  row.getDocNo(),
                  row.getSupplierInvoiceNo(),
                  row.getCompanyId(),
                  company == null ? null : company.name(),
                  row.getSupplierId(),
                  supplier == null ? null : supplier.name(),
                  row.getPoId(),
                  order == null ? null : order.getDocNo(),
                  row.getInvoiceDate(),
                  row.getCurrency(),
                  row.getNetAmount(),
                  row.getTaxAmount(),
                  row.getTotalAmount(),
                  row.getPaidAmount(),
                  row.getStatus(),
                  row.getVersion());
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public PaymentView payment(PaymentRequest payment) {
    CompanyBrief company = masterDataQueryFacade.findCompany(payment.getCompanyId()).orElse(null);
    SupplierBrief supplier = supplierQueryFacade.findById(payment.getSupplierId()).orElse(null);
    SupplierInvoice invoice = invoiceMapper.findById(payment.getInvoiceId()).orElse(null);
    UserBrief requester = userQueryFacade.findById(payment.getRequestedBy()).orElse(null);
    UserBrief approver =
        payment.getApprovedBy() == null
            ? null
            : userQueryFacade.findById(payment.getApprovedBy()).orElse(null);
    return new PaymentView(
        payment.getId(),
        payment.getDocNo(),
        payment.getCompanyId(),
        company == null ? null : company.name(),
        payment.getSupplierId(),
        supplier == null ? null : supplier.name(),
        payment.getInvoiceId(),
        invoice == null ? null : invoice.getDocNo(),
        invoice == null ? null : invoice.getSupplierInvoiceNo(),
        payment.getAmount(),
        payment.getCurrency(),
        payment.getStatus(),
        payment.getRequestedBy(),
        requester == null ? null : requester.name(),
        payment.getApprovedBy(),
        approver == null ? null : approver.name(),
        payment.getApprovedAt(),
        payment.getPaidAt(),
        payment.getVersion(),
        payment.getCreatedAt());
  }

  @Transactional(readOnly = true)
  public List<PaymentSummaryView> payments(List<PaymentRequest> rows) {
    Map<Long, CompanyBrief> companies = companiesOf(rows.stream().map(PaymentRequest::getCompanyId).toList());
    Map<Long, SupplierBrief> suppliers =
        supplierQueryFacade.findByIds(rows.stream().map(PaymentRequest::getSupplierId).toList()).stream()
            .collect(Collectors.toMap(SupplierBrief::id, Function.identity()));
    Map<Long, SupplierInvoice> invoices = invoicesOf(rows.stream().map(PaymentRequest::getInvoiceId).toList());
    return rows.stream()
        .map(
            row -> {
              CompanyBrief company = companies.get(row.getCompanyId());
              SupplierBrief supplier = suppliers.get(row.getSupplierId());
              SupplierInvoice invoice = invoices.get(row.getInvoiceId());
              return new PaymentSummaryView(
                  row.getId(),
                  row.getDocNo(),
                  row.getCompanyId(),
                  company == null ? null : company.name(),
                  row.getSupplierId(),
                  supplier == null ? null : supplier.name(),
                  row.getInvoiceId(),
                  invoice == null ? null : invoice.getDocNo(),
                  row.getAmount(),
                  row.getCurrency(),
                  row.getStatus(),
                  row.getCreatedAt());
            })
        .toList();
  }

  private Map<Long, CompanyBrief> companiesOf(Collection<Long> ids) {
    return ids.stream()
        .filter(Objects::nonNull)
        .distinct()
        .map(id -> masterDataQueryFacade.findCompany(id).orElse(null))
        .filter(Objects::nonNull)
        .collect(Collectors.toMap(CompanyBrief::id, Function.identity()));
  }

  private Map<Long, PurchaseOrder> ordersOf(Collection<Long> ids) {
    List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
    return distinct.isEmpty()
        ? Map.of()
        : purchaseOrderMapper.findByIds(distinct).stream()
            .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
  }

  private Map<Long, SupplierInvoice> invoicesOf(Collection<Long> ids) {
    List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
    return distinct.isEmpty()
        ? Map.of()
        : invoiceMapper.findByIds(distinct).stream()
            .collect(Collectors.toMap(SupplierInvoice::getId, Function.identity()));
  }

  private Map<Long, ItemBrief> itemsOf(Collection<Long> ids) {
    return masterDataQueryFacade.findItems(ids).stream()
        .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
  }

  private static InvoiceLineView line(SupplierInvoiceLine line, Map<Long, ItemBrief> items) {
    ItemBrief item = items.get(line.getItemId());
    return new InvoiceLineView(
        line.getId(),
        line.getLineNo(),
        line.getPoLineId(),
        line.getItemId(),
        item == null ? null : item.code(),
        item == null ? null : item.name(),
        line.getQty(),
        line.getUnitPrice(),
        line.getAmount());
  }
}
