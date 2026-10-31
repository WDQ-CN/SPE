package com.spe.context.system.internal.web.dto;

import com.spe.context.system.internal.security.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** ADMIN 重置他人口令入参（01 SM1 / 02 §4 `PUT /users/{id}/password`）。 */
public record ResetPasswordRequest(
    @NotBlank(message = "新口令不能为空") @Size(min = PasswordPolicy.MIN_LENGTH, message = "新口令长度不得少于 8 位") String newPassword) {}
