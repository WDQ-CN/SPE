package com.spe.context.sales.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 创建客户收款并核销发票。 */
public record CreateCustomerReceiptRequest(
    @NotNull(message = "客户不能为空") Long customerId,
    @Size(max = 32, message = "收款方式长度不得超过 32") String method,
    @NotEmpty(message = "至少需要核销一张发票") @Valid List<Allocation> allocations) {

  public record Allocation(
      @NotNull(message = "发票不能为空") Long invoiceId,
      @NotNull(message = "核销金额不能为空")
          @DecimalMin(value = "0.0001", message = "核销金额必须大于 0")
          BigDecimal amount) {}
}
