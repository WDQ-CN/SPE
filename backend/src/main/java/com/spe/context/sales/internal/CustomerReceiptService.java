package com.spe.context.sales.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CustomerBrief;
import com.spe.context.masterdata.api.CustomerCreditFacade;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.sales.api.events.CustomerReceiptPostedEvent;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 客户收款与核销（docs/erp/35）：部分收款、发票状态推进与信用释放。 */
@Service
@RequiredArgsConstructor
public class CustomerReceiptService {

  private final CustomerReceiptMapper receiptMapper;
  private final ReceiptAllocationMapper allocationMapper;
  private final SalesInvoiceMapper invoiceMapper;
  private final CustomerCreditFacade customerCreditFacade;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  @Transactional
  public CustomerReceiptView create(
      long operatorId, long customerId, String method, List<AllocationCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.RECEIPT_ALLOCATION_INVALID, "至少需要核销一张发票");
    }
    CustomerBrief customer =
        masterDataQueryFacade
            .findCustomer(customerId)
            .filter(CustomerBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.CUSTOMER_NOT_FOUND));
    BigDecimal total = BigDecimal.ZERO;
    List<SalesInvoice> invoices = new ArrayList<>();
    for (AllocationCommand command : commands) {
      SalesInvoice invoice =
          invoiceMapper
              .lockById(command.invoiceId())
              .orElseThrow(() -> new BizException(ErrorCode.SALES_INVOICE_NOT_FOUND));
      if (invoice.getCustomerId() != customerId) {
        throw new BizException(ErrorCode.RECEIPT_ALLOCATION_INVALID, "发票不属于该客户");
      }
      if (invoice.getStatus() != SalesInvoiceStatus.ISSUED
          && invoice.getStatus() != SalesInvoiceStatus.PARTIAL) {
        throw new BizException(ErrorCode.RECEIPT_ALLOCATION_INVALID, "发票状态不可核销");
      }
      if (command.amount() == null || command.amount().signum() <= 0) {
        throw new BizException(ErrorCode.RECEIPT_ALLOCATION_INVALID, "核销金额必须大于 0");
      }
      BigDecimal outstanding = invoice.getTotalAmount().subtract(invoice.getPaidAmount());
      if (command.amount().compareTo(outstanding) > 0) {
        throw new BizException(ErrorCode.RECEIPT_ALLOCATION_INVALID, "核销金额超过未收金额");
      }
      invoices.add(invoice);
      total = total.add(command.amount());
    }
    SalesInvoice first = invoices.get(0);
    for (SalesInvoice invoice : invoices) {
      if (!Objects.equals(invoice.getCurrency(), first.getCurrency())
          || !Objects.equals(invoice.getCompanyId(), first.getCompanyId())) {
        throw new BizException(ErrorCode.RECEIPT_ALLOCATION_INVALID, "核销发票必须同公司同币种");
      }
    }
    BigDecimal amount = SalesSupport.money(total);
    CustomerReceipt receipt = new CustomerReceipt();
    receipt.setReceiptNo(SalesSupport.docNo("RCP"));
    receipt.setCompanyId(first.getCompanyId());
    receipt.setCustomerId(customerId);
    receipt.setCurrency(first.getCurrency());
    receipt.setAmount(amount);
    receipt.setMethod(method == null || method.isBlank() ? "BANK" : method.trim().toUpperCase());
    receipt.setStatus(ReceiptStatus.POSTED);
    receipt.setVersion(0);
    receipt.setCreatedBy(operatorId);
    receiptMapper.insert(receipt);

    for (int i = 0; i < commands.size(); i++) {
      AllocationCommand command = commands.get(i);
      SalesInvoice invoice = invoices.get(i);
      BigDecimal allocated = command.amount().setScale(4, RoundingMode.HALF_UP);
      if (invoiceMapper.increasePaid(invoice.getId(), allocated) == 0) {
        throw new BizException(ErrorCode.RECEIPT_ALLOCATION_INVALID, "发票已收款金额校验失败");
      }
      ReceiptAllocation allocation = new ReceiptAllocation();
      allocation.setReceiptId(receipt.getId());
      allocation.setInvoiceId(invoice.getId());
      allocation.setAmount(allocated);
      allocationMapper.insert(allocation);
    }
    customerCreditFacade.decreaseUsed(customerId, amount);
    eventPublisher.publish(
        new CustomerReceiptPostedEvent(
            receipt.getId(),
            receipt.getCompanyId(),
            customerId,
            receipt.getCurrency(),
            amount,
            LocalDate.now(ZoneOffset.UTC)));
    auditFacade.record(
        AuditAction.CUSTOMER_RECEIPT_POST,
        AuditTargetType.CUSTOMER_RECEIPT,
        receipt.getId(),
        Map.of(
            "receiptNo", receipt.getReceiptNo(),
            "customerId", customerId,
            "invoiceCount", commands.size(),
            "amount", amount));
    return detail(receipt.getId());
  }

  @Transactional(readOnly = true)
  public PageResult<CustomerReceipt> page(
      int page, int size, Long companyId, Long customerId) {
    PageQuery query = PageQuery.of(page, size);
    return PageResult.of(
        receiptMapper.findPage(companyId, customerId, query.offset(), query.size()),
        receiptMapper.countBy(companyId, customerId),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public CustomerReceiptView detail(long id) {
    CustomerReceipt receipt =
        receiptMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.CUSTOMER_RECEIPT_NOT_FOUND));
    CustomerBrief customer =
        masterDataQueryFacade.findCustomer(receipt.getCustomerId()).orElse(null);
    List<CustomerReceiptView.Allocation> allocations =
        allocationMapper.findByReceiptId(id).stream()
            .map(
                allocation -> {
                  SalesInvoice invoice =
                      invoiceMapper.findById(allocation.getInvoiceId()).orElse(null);
                  return new CustomerReceiptView.Allocation(
                      allocation.getId(),
                      allocation.getInvoiceId(),
                      invoice == null ? null : invoice.getInvoiceNo(),
                      allocation.getAmount());
                })
            .toList();
    return new CustomerReceiptView(
        receipt.getId(),
        receipt.getReceiptNo(),
        receipt.getCompanyId(),
        receipt.getCustomerId(),
        customer == null ? null : customer.name(),
        receipt.getCurrency(),
        receipt.getAmount(),
        receipt.getMethod(),
        receipt.getStatus(),
        receipt.getReceivedAt(),
        receipt.getVersion(),
        allocations);
  }

  /** 收款核销命令。 */
  public record AllocationCommand(long invoiceId, BigDecimal amount) {}
}
