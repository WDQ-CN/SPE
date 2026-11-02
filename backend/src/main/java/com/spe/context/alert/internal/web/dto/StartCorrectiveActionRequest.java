package com.spe.context.alert.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 开始整改（W3；OPEN/REOPENED → IN_PROGRESS）。 */
public record StartCorrectiveActionRequest(
    @NotNull(message = "缺少乐观锁版本") @Min(value = 0, message = "乐观锁版本不合法")
        Integer version) {}
