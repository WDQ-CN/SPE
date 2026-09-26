package com.spe.context.system.internal.web.dto;

import com.spe.context.system.api.UserStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 启停账号入参（01 A4/SM1 / 02 §4 `PUT /users/{id}/status`）。
 *
 * <p>只传**目标状态**；前置状态由后端按两态机推出（`UserStatus.other()`），客户端无法伪造「从哪来」，
 * 这正是守卫 UPDATE 需要的条件（10 §E12）。
 */
public record ChangeStatusRequest(
    @NotNull(message = "目标状态不能为空") UserStatus status,
    @NotNull(message = "版本号不能为空") @Min(value = 0, message = "版本号不能为负") Integer version) {}
