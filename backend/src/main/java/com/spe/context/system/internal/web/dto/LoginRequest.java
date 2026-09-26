package com.spe.context.system.internal.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 登录入参（02 §4 `POST /auth/login`）。口令只做非空校验，规则细节在后端 {@code PasswordPolicy}。 */
public record LoginRequest(
    @NotBlank(message = "账号不能为空") @Size(max = 50, message = "账号长度不得超过 50") String username,
    @NotBlank(message = "口令不能为空") String password) {}
