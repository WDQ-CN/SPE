package com.spe.context.alert.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 提交完成（W3；IN_PROGRESS → WAITING_VERIFICATION），提交说明可选。 */
public record SubmitCorrectiveActionRequest(
    @NotNull(message = "缺少乐观锁版本") @Min(value = 0, message = "乐观锁版本不合法")
        Integer version,
    @Size(max = 500, message = "提交说明不得超过 500 字") String comment) {}
