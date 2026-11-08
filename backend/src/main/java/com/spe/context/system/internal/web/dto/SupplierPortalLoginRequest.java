package com.spe.context.system.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 门户登录。 */
public record SupplierPortalLoginRequest(
    @NotBlank(message = "登录名不能为空") @Size(max = 64, message = "登录名长度不得超过 64")
        String loginName,
    @NotBlank(message = "口令不能为空") String password) {}
