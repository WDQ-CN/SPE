package com.spe.context.system.internal.user;

import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserStatus;

/**
 * 用户列表行（HTTP 响应投影）。带 `version` 是因为启停与编辑都要求前端回传版本做守卫条件
 * （02 §4 的 `PUT /users/{id}/status`、`PUT /users/{id}`）。
 */
public record UserSummary(
    Long id,
    String username,
    String name,
    Role role,
    Department department,
    UserStatus status,
    Integer version) {

  static UserSummary of(SysUser user) {
    return new UserSummary(
        user.getId(),
        user.getUsername(),
        user.getName(),
        user.getRole(),
        user.getDepartment(),
        user.getStatus(),
        user.getVersion());
  }
}
