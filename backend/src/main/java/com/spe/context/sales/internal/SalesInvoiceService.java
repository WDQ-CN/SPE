package com.spe.context.sales.internal;

import com.spe.common.event.EventPublisher;
import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.masterdata.api.CustomerBrief;
import com.spe.context.masterdata.api.CustomerCreditFacade;
import com.spe.context.masterdata.api.ItemBrief;
import com.spe.context.masterdata.api.MasterDataQueryFacade;
import com.spe.context.sales.api.events.SalesInvoicedEvent;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 销售发票（docs/erp/35）：按已发数量开票，占用客户信用，并发布财务事件。 */
@Service
@RequiredArgsConstructor
public class SalesInvoiceService {

  private final SalesInvoiceMapper invoiceMapper;
  private final SalesInvoiceLineMapper invoiceLineMapper;
  private final SalesOrderMapper orderMapper;
  private final SalesOrderLineMapper orderLineMapper;
  private final CustomerCreditFacade customerCreditFacade;
  private final MasterDataQueryFacade masterDataQueryFacade;
  private final EventPublisher eventPublisher;
  private final AuditFacade auditFacade;

  @Transactional
  public SalesInvoiceView create(
      long operatorId,
      long orderId,
      LocalDate dueDate,
      String remark,
      List<InvoiceLineCommand> commands) {
    if (commands == null || commands.isEmpty()) {
      throw new BizException(ErrorCode.SALES_INVOICE_LINE_INVALID, "至少需要一行开票明细");
    }
    SalesOrder order =
        orderMapper
            .lockById(orderId)
            .orElseThrow(() -> new BizException(ErrorCode.SALES_ORDER_NOT_FOUND));
    if (order.getStatus() != SalesOrderStatus.SHIPPED
        && order.getStatus() != SalesOrderStatus.PARTIAL_SHIPPED) {
      throw new BizException(ErrorCode.SALES_ORDER_STATUS_INVALID, "订单尚未发货，不能开票");
    }
    if (dueDate == null || dueDate.isBefore(LocalDate.now(ZoneOffset.UTC))) {
      throw new BizException(ErrorCode.BAD_REQUEST, "到期日不能早于今天");
    }
    CustomerBrief customer =
        masterDataQueryFacade
            .findCustomer(order.getCustomerId())
            .filter(CustomerBrief::active)
            .orElseThrow(() -> new BizException(ErrorCode.CUSTOMER_NOT_FOUND));

    SalesInvoice invoice = new SalesInvoice();
    invoice.setInvoiceNo(SalesSupport.docNo("SI"));
    invoice.setCompanyId(order.getCompanyId());
    invoice.setCustomerId(order.getCustomerId());
    invoice.setOrderId(orderId);
    invoice.setCurrency(order.getCurrency());
    invoice.setStatus(SalesInvoiceStatus.ISSUED);
    invoice.setPaidAmount(BigDecimal.ZERO);
    invoice.setDueDate(dueDate);
    invoice.setRemark(remark);
    invoice.setVersion(0);
    invoice.setCreatedBy(operatorId);
    invoiceMapper.insert(invoice);

    BigDecimal netTotal = BigDecimal.ZERO;
    BigDecimal taxTotal = BigDecimal.ZERO;
    int lineNo = 1;
    for (InvoiceLineCommand command : commands) {
      SalesOrderLine orderLine =
          orderLineMapper
              .findByIdAndOrder(command.orderLineId(), orderId)
              .orElseThrow(() -> new BizException(ErrorCode.SALES_INVOICE_LINE_INVALID));
      if (command.qty() == null || command.qty().signum() <= 0) {
        throw new BizException(ErrorCode.SALES_INVOICE_LINE_INVALID, "开票数量必须大于 0");
      }
      BigDecimal available = orderLine.getShippedQty().subtract(orderLine.getInvoicedQty());
      if (command.qty().compareTo(available) > 0) {
        throw new BizException(
            ErrorCode.SALES_INVOICE_LINE_INVALID, "开票数量超过可开票数量：" + available);
      }
      BigDecimal qty = command.qty().setScale(6, RoundingMode.HALF_UP);
      BigDecimal net =
          SalesSupport.money(
              qty.multiply(orderLine.getUnitPrice())
                  .multiply(BigDecimal.ONE.subtract(orderLine.getDiscountRate())));
      BigDecimal tax = SalesSupport.money(net.multiply(orderLine.getTaxRate()));
      SalesInvoiceLine line = new SalesInvoiceLine();
      line.setInvoiceId(invoice.getId());
      line.setLineNo(lineNo++);
      line.setOrderLineId(orderLine.getId());
      line.setItemId(orderLine.getItemId());
      line.setQty(qty);
      line.setUnitPrice(orderLine.getUnitPrice());
      line.setDiscountRate(orderLine.getDiscountRate());
      line.setTaxRate(orderLine.getTaxRate());
      line.setNetAmount(net);
      line.setTaxAmount(tax);
      invoiceLineMapper.insert(line);
      if (orderLineMapper.increaseInvoiced(orderLine.getId(), orderId, qty) == 0) {
        throw new BizException(ErrorCode.SALES_INVOICE_LINE_INVALID, "订单行已开票数量校验失败");
      }
      netTotal = netTotal.add(net);
      taxTotal = taxTotal.add(tax);
    }
    BigDecimal total = SalesSupport.money(netTotal.add(taxTotal));
    invoice.setNetAmount(SalesSupport.money(netTotal));
    invoice.setTaxAmount(SalesSupport.money(taxTotal));
    invoice.setTotalAmount(total);
    invoiceMapper.updateById(invoice);

    customerCreditFacade.increaseUsed(customer.id(), total);
    eventPublisher.publish(
        new SalesInvoicedEvent(
            invoice.getId(),
            invoice.getCompanyId(),
            invoice.getCustomerId(),
            invoice.getCurrency(),
            invoice.getNetAmount(),
            invoice.getTaxAmount(),
            total,
            LocalDate.now(ZoneOffset.UTC)));
    auditFacade.record(
        AuditAction.SALES_INVOICE_ISSUE,
        AuditTargetType.SALES_INVOICE,
        invoice.getId(),
        Map.of(
            "invoiceNo", invoice.getInvoiceNo(),
            "orderId", orderId,
            "lineCount", commands.size(),
            "total", total));
    return detail(invoice.getId());
  }

  @Transactional(readOnly = true)
  public PageResult<SalesInvoice> page(
      int page, int size, Long companyId, Long customerId, String status) {
    PageQuery query = PageQuery.of(page, size);
    SalesInvoiceStatus parsed =
        status == null || status.isBlank()
            ? null
            : parseStatus(status);
    return PageResult.of(
        invoiceMapper.findPage(companyId, customerId, parsed, query.offset(), query.size()),
        invoiceMapper.countBy(companyId, customerId, parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public SalesInvoiceView detail(long id) {
    SalesInvoice invoice =
        invoiceMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.SALES_INVOICE_NOT_FOUND));
    CustomerBrief customer =
        masterDataQueryFacade.findCustomer(invoice.getCustomerId()).orElse(null);
    List<SalesInvoiceLine> lines = invoiceLineMapper.findByInvoiceId(id);
    Map<Long, ItemBrief> items =
        masterDataQueryFacade
            .findItems(lines.stream().map(SalesInvoiceLine::getItemId).toList())
            .stream()
            .collect(Collectors.toMap(ItemBrief::id, Function.identity()));
    return new SalesInvoiceView(
        invoice.getId(),
        invoice.getInvoiceNo(),
        invoice.getCompanyId(),
        invoice.getCustomerId(),
        customer == null ? null : customer.name(),
        invoice.getOrderId(),
        invoice.getCurrency(),
        invoice.getStatus(),
        invoice.getNetAmount(),
        invoice.getTaxAmount(),
        invoice.getTotalAmount(),
        invoice.getPaidAmount(),
        invoice.getIssuedAt(),
        invoice.getDueDate(),
        invoice.getRemark(),
        invoice.getVersion(),
        lines.stream()
            .map(
                line -> {
                  ItemBrief item = items.get(line.getItemId());
                  return new SalesInvoiceLineView(
                      line.getId(),
                      line.getLineNo(),
                      line.getOrderLineId(),
                      line.getItemId(),
                      item == null ? null : item.code(),
                      item == null ? null : item.name(),
                      line.getQty(),
                      line.getUnitPrice(),
                      line.getDiscountRate(),
                      line.getTaxRate(),
                      line.getNetAmount(),
                      line.getTaxAmount());
                })
            .toList());
  }

  @Transactional(readOnly = true)
  public ArAgingView arAging(long companyId, LocalDate asOf) {
    LocalDate effectiveDate = asOf == null ? LocalDate.now(ZoneOffset.UTC) : asOf;
    List<SalesInvoice> invoices =
        invoiceMapper.findOpen(companyId, effectiveDate.plusDays(1).atStartOfDay());
    Map<Long, CustomerBrief> customers =
        masterDataQueryFacade
            .findCustomers(invoices.stream().map(SalesInvoice::getCustomerId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(CustomerBrief::id, Function.identity()));
    BigDecimal current = BigDecimal.ZERO;
    BigDecimal days1To30 = BigDecimal.ZERO;
    BigDecimal days31To60 = BigDecimal.ZERO;
    BigDecimal days61To90 = BigDecimal.ZERO;
    BigDecimal daysOver90 = BigDecimal.ZERO;
    List<ArAgingView.Row> rows = new java.util.ArrayList<>();
    for (SalesInvoice invoice : invoices) {
      BigDecimal outstanding = invoice.getTotalAmount().subtract(invoice.getPaidAmount());
      if (outstanding.signum() <= 0) {
        continue;
      }
      long overdue = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(invoice.getDueDate(), effectiveDate));
      String bucket;
      if (overdue == 0) {
        bucket = "CURRENT";
        current = current.add(outstanding);
      } else if (overdue <= 30) {
        bucket = "1-30";
        days1To30 = days1To30.add(outstanding);
      } else if (overdue <= 60) {
        bucket = "31-60";
        days31To60 = days31To60.add(outstanding);
      } else if (overdue <= 90) {
        bucket = "61-90";
        days61To90 = days61To90.add(outstanding);
      } else {
        bucket = "90+";
        daysOver90 = daysOver90.add(outstanding);
      }
      CustomerBrief customer = customers.get(invoice.getCustomerId());
      rows.add(
          new ArAgingView.Row(
              invoice.getId(),
              invoice.getInvoiceNo(),
              invoice.getCustomerId(),
              customer == null ? null : customer.name(),
              invoice.getDueDate(),
              overdue,
              bucket,
              outstanding));
    }
    BigDecimal total = current.add(days1To30).add(days31To60).add(days61To90).add(daysOver90);
    return new ArAgingView(
        companyId,
        effectiveDate,
        total,
        current,
        days1To30,
        days31To60,
        days61To90,
        daysOver90,
        rows);
  }

  private static SalesInvoiceStatus parseStatus(String status) {
    try {
      return SalesInvoiceStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "发票状态不合法：" + status);
    }
  }

  /** 开票行命令。 */
  public record InvoiceLineCommand(long orderLineId, BigDecimal qty) {}
}
