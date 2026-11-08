package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** 内部创建竞价。 */
public record CreateAuctionRequest(
    @NotBlank(message = "标题不能为空") @Size(max = 128, message = "标题长度不得超过 128")
        String title,
    @Positive(message = "公司 id 必须为正") long companyId,
    @Pattern(regexp = "[A-Za-z]{3}", message = "币种必须为 3 位字母") String currency,
    @NotNull(message = "开始时间不能为空") LocalDateTime startAt,
    @NotNull(message = "结束时间不能为空") LocalDateTime endAt,
    Boolean sealed,
    @Min(value = 0, message = "防狙击秒数不能为负") @Max(value = 600, message = "防狙击秒数不得超过 600")
        Integer antiSnipingSeconds,
    @Min(value = 0, message = "最大延期次数不能为负") @Max(value = 10, message = "最大延期次数不得超过 10")
        Integer maxExtensions,
    @NotEmpty(message = "至少需要一个竞价批次") List<@Valid AuctionLotRequest> lots,
    @NotEmpty(message = "至少邀请一个供应商") List<@Positive(message = "供应商 id 必须为正") Long>
        supplierIds) {}
