package com.spe.context.system.internal.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 门户刷新/登出令牌。 */
public record SupplierPortalRefreshRequest(@NotBlank(message = "刷新令牌不能为空") String refreshToken) {}
