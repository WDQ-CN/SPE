package com.spe.context.supplier.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 供应商门户账号创建（供应商 id 在路径里）。 */
public record CreateSupplierPortalAccountRequest(
    @NotBlank(message = "登录名不能为空") @Size(max = 64, message = "登录名长度不得超过 64")
        String loginName,
    @NotBlank(message = "初始口令不能为空") @Size(min = 8, max = 100, message = "口令长度不得少于 8 位")
        String password) {}
