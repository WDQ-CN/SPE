package com.spe.context.system.internal.security;

import java.security.Principal;

/**
 * 已认证操作人的身份（09 D6 的令牌 claims：`uid` + `sub`）。
 *
 * <p>作为安全上下文里的 principal：实现 {@link Principal} 后 `Authentication.getName()` 仍返回账号
 * （Spring Security 对 `Principal` 的处理与字符串 principal 一致），同时把令牌里的 `uid` 一并带出来——
 * 审计要写 `audit_log.operator_id`，P3 的数据可见性过滤也要按 uid 收敛，
 * 都不必再为了一个 id 去回查用户表。
 */
public record AuthenticatedOperator(long uid, String username) implements Principal {

  @Override
  public String getName() {
    return username;
  }
}
