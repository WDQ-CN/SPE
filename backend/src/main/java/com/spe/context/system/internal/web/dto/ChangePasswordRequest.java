package com.spe.context.system.internal.web.dto;

import com.spe.context.system.internal.security.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 本人改口令入参（01 A3 / 10 §E3）：新口令长度下限与 {@code PasswordPolicy.MIN_LENGTH} 一致。 */
public record ChangePasswordRequest(
    @NotBlank(message = "原口令不能为空") String oldPassword,
    @NotBlank(message = "新口令不能为空") @Size(min = PasswordPolicy.MIN_LENGTH, message = "新口令长度不得少于 8 位") String newPassword) {}
