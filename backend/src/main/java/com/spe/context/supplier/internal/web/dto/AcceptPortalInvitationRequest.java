package com.spe.context.supplier.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 接受门户邀请：创建门户账号。 */
public record AcceptPortalInvitationRequest(
    @NotBlank(message = "登录名不能为空") @Size(max = 64, message = "登录名长度不得超过 64")
        String loginName,
    @NotBlank(message = "口令不能为空") @Size(max = 72, message = "口令长度不得超过 72")
        String password) {}
