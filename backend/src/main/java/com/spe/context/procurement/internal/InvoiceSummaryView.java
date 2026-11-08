package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.SupplierInvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 供应商发票列表投影。 */
public record InvoiceSummaryView(
    long id,
    String docNo,
    String supplierInvoiceNo,
    long companyId,
    String companyName,
    long supplierId,
    String supplierName,
    long poId,
    String poDocNo,
    LocalDate invoiceDate,
    String currency,
    BigDecimal netAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    SupplierInvoiceStatus status,
    int version) {}
