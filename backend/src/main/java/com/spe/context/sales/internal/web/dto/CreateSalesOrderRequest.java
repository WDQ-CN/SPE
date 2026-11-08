package com.spe.context.sales.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 创建销售订单。 */
public record CreateSalesOrderRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotNull(message = "客户不能为空") Long customerId,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @Size(max = 32, message = "付款条件长度不得超过 32") String paymentTerms,
    @NotEmpty(message = "订单至少需要一行") @Valid List<SalesLineRequest> lines) {}
