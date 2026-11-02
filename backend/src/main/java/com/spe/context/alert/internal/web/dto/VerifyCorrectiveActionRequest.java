package com.spe.context.alert.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 经理验证（W3；WAITING_VERIFICATION → VERIFIED / REOPENED）。
 *
 * <p>`pass=true` 为验证通过，`false` 为继续整改。下期结果缺失或为 PENDING 时，
 * Service 要求必须填写意见，避免「人工判断」没有依据。
 */
public record VerifyCorrectiveActionRequest(
    @NotNull(message = "缺少乐观锁版本") @Min(value = 0, message = "乐观锁版本不合法")
        Integer version,
    @NotNull(message = "请选择验证结论") Boolean pass,
    @Size(max = 500, message = "验证意见不得超过 500 字") String comment) {}
