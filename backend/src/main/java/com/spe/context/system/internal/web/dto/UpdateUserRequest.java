package com.spe.context.system.internal.web.dto;

import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 编辑用户入参（02 §4 `PUT /users/{id}`）。
 *
 * <p>`version` 必填：它是守卫 UPDATE 的前置条件（06 §2.4），缺少它就无法区分「改到的是我看到的那一行」。
 * `username` 不在入参里——账号是登录标识，P1 不支持改账号。
 */
public record UpdateUserRequest(
    @NotBlank(message = "姓名不能为空") @Size(max = 50, message = "姓名长度不得超过 50") String name,
    @NotNull(message = "角色不能为空") Role role,
    @NotNull(message = "部门不能为空") Department department,
    @NotNull(message = "版本号不能为空") @Min(value = 0, message = "版本号不能为负") Integer version) {}
