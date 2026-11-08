package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** 创建 RFQ。 */
public record CreateRfqRequest(
    @NotNull(message = "公司不能为空") Long companyId,
    @NotNull(message = "工厂不能为空") Long plantId,
    @NotBlank(message = "标题不能为空") @Size(max = 200, message = "标题长度不得超过 200") String title,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @NotNull(message = "截止时间不能为空") LocalDateTime deadlineAt,
    Boolean sealed,
    Long sourcePrId,
    @Size(max = 16, message = "定标策略长度不得超过 16") String awardPolicy,
    @NotEmpty(message = "RFQ 至少需要一行") @Valid List<CreateRfqLineRequest> lines) {}
