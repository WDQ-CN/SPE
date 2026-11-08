package com.spe.context.procurement.internal;

import com.spe.context.procurement.api.SupplierInvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 供应商发票详情投影（含三单匹配结论）。 */
public record InvoiceView(
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
    LocalDate dueDate,
    String currency,
    BigDecimal netAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    BigDecimal paidAmount,
    SupplierInvoiceStatus status,
    long createdBy,
    String createdByName,
    Long approvedBy,
    String approvedByName,
    LocalDateTime approvedAt,
    int version,
    LocalDateTime createdAt,
    MatchResultView match,
    List<InvoiceLineView> lines) {}
