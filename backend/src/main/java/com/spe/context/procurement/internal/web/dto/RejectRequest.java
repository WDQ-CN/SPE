package com.spe.context.procurement.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 驳回：必须带意见（06 §2.8 的 detail 截断由服务层处理）。 */
public record RejectRequest(
    @NotBlank(message = "驳回意见不能为空") @Size(max = 500, message = "意见长度不得超过 500")
        String comment,
    @PositiveOrZero(message = "版本号不能为负") int version) {}
