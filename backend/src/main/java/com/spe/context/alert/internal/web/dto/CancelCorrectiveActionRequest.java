package com.spe.context.alert.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 取消整改（W3；仅未完成状态可取消，必须写原因）。 */
public record CancelCorrectiveActionRequest(
    @NotNull(message = "缺少乐观锁版本") @Min(value = 0, message = "乐观锁版本不合法")
        Integer version,
    @NotBlank(message = "取消原因不能为空")
        @Size(max = 500, message = "取消原因不得超过 500 字")
        String reason) {}
