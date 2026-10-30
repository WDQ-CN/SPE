package com.spe.context.evaluation.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 解锁重评的入参（M5 W3）：版本是乐观锁；意见可选、最长 500。
 *
 * <p>版本用 `@Min(0)` 而不是 `@Positive`：本项目的新行 version 从 **0** 开始（P1 起的一致口径），
 * 把 0 判成非法会让"刚定稿、还没人动过"的评估单无法解锁。
 */
public record UnlockRequest(
    @NotNull(message = "缺少乐观锁版本") @Min(value = 0, message = "乐观锁版本不合法") Integer version,
    @Size(max = 500, message = "解锁意见不得超过 500 字") String comment) {}
