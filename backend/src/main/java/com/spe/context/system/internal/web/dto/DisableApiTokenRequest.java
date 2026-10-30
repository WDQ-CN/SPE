package com.spe.context.system.internal.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 停用开放 API Token（M6）：version 是乐观锁，防止两个管理员同时停用/旧页面误操作。 */
public record DisableApiTokenRequest(
    @NotNull(message = "版本号不能为空") @Min(value = 0, message = "版本号不能为负") Integer version) {}
