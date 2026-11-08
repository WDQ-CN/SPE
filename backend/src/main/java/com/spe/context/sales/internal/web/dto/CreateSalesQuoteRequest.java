package com.spe.context.sales.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;

/** 创建销售报价。 */
public record CreateSalesQuoteRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotNull(message = "客户不能为空") Long customerId,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @NotNull(message = "有效期不能为空") LocalDate validUntil,
    @NotEmpty(message = "报价至少需要一行") @Valid List<SalesLineRequest> lines) {}
