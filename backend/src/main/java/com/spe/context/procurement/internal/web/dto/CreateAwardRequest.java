package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 创建定标。 */
public record CreateAwardRequest(
    @NotNull(message = "供应商不能为空") Long supplierId,
    @Size(max = 255, message = "定标理由长度不得超过 255") String reason,
    @NotEmpty(message = "定标至少需要一行") @Valid List<CreateAwardLineRequest> lines) {}
