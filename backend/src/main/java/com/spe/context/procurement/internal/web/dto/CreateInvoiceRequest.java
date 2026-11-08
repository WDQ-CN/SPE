package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 录入供应商发票。 */
public record CreateInvoiceRequest(
    @Positive(message = "采购订单 id 必须为正") long poId,
    @NotBlank(message = "供应商发票号不能为空")
        @Size(max = 64, message = "供应商发票号长度不得超过 64")
        String supplierInvoiceNo,
    @NotNull(message = "发票日期不能为空") LocalDate invoiceDate,
    LocalDate dueDate,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @NotNull(message = "税额不能为空")
        @DecimalMin(value = "0", message = "税额不能为负")
        @Digits(integer = 15, fraction = 4, message = "税额精度不得超过 4 位小数")
        BigDecimal taxAmount,
    @NotEmpty(message = "至少需要一行发票明细") List<@Valid InvoiceLineRequest> lines) {}
