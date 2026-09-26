package com.spe.context.system.internal.web.dto;

import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 新增用户入参（01 SM1 / 02 §4 `POST /users`）。新建账号一律 `ENABLED`，不能在这里指定状态。 */
public record CreateUserRequest(
    @NotBlank(message = "账号不能为空") @Size(max = 50, message = "账号长度不得超过 50") String username,
    @NotBlank(message = "初始口令不能为空") @Size(min = 8, message = "初始口令长度不得少于 8 位") String password,
    @NotBlank(message = "姓名不能为空") @Size(max = 50, message = "姓名长度不得超过 50") String name,
    @NotNull(message = "角色不能为空") Role role,
    @NotNull(message = "部门不能为空") Department department) {}
