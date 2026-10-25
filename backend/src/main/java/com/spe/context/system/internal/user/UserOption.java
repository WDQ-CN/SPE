package com.spe.context.system.internal.user;

import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;

/**
 * 「可选人员」投影（02 §4 / 03 §38 P0-1）：给「审批链」与「改派指派」两个选择器用。
 *
 * <p>与 {@link UserSummary} 的区别是刻意的：这里**不带** {@code username}、不带口令、也不带
 * {@code version}——选择器要的是"选谁"，不是"管理谁"。把管理面（含守卫 UPDATE 需要的版本号）
 * 从一个供 MANAGER 调用的端点漏出去，等于把 `/api/users` 的权限边界换个名字重开一次。
 */
public record UserOption(Long id, String name, Role role, Department department) {

  static UserOption of(SysUser user) {
    return new UserOption(user.getId(), user.getName(), user.getRole(), user.getDepartment());
  }
}
