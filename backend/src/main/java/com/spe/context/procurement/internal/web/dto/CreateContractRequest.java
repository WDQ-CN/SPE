package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 创建采购合同/框架协议。 */
public record CreateContractRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotNull(message = "供应商不能为空") Long supplierId,
    @NotBlank(message = "标题不能为空") @Size(max = 200, message = "标题长度不得超过 200") String title,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @NotNull(message = "生效日不能为空") LocalDate validFrom,
    @NotNull(message = "失效日不能为空") LocalDate validTo,
    @Size(max = 32, message = "付款条件长度不得超过 32") String paymentTerms,
    @DecimalMin(value = "0.00", message = "金额上限不能为负") BigDecimal totalLimitAmount,
    @DecimalMin(value = "0.0", message = "数量上限不能为负") BigDecimal totalLimitQty,
    @NotEmpty(message = "合同至少需要一行") @Valid List<CreateContractLineRequest> lines) {}
