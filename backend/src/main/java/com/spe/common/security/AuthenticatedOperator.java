package com.spe.common.security;

import java.security.Principal;

/**
 * 已认证操作人的身份（09 D6 的令牌 claims：`uid` + `sub`）。
 *
 * <p>作为安全上下文里的 principal：实现 {@link Principal} 后 `Authentication.getName()` 仍返回账号
 * （Spring Security 对 `Principal` 的处理与字符串 principal 一致），同时把令牌里的 uid 一并带出来——
 * 审计要写 `audit_log.operator_id`，**数据可见性过滤**（P3 的"只看指派给我的评分单"）也要按 uid 收敛，
 * 都不必再为了一个 id 去回查用户表。
 *
 * <p>⚠️ **为什么在 common 而不是 system.internal**（2026-10-01，P3-W5 搬过来的）：P1 时它只被 system
 * 自己的审计用到，放在 internal 没问题；P3 起 evaluation（评分单可见性）与后续上下文都要读它，而
 * `internal` 包按 05 §4.3 永不外泄。它是**零依赖的记录类型**，属于共享内核该有的东西（R-F 只禁止
 * common 依赖上下文，不禁止上下文依赖 common）。
 */
public record AuthenticatedOperator(long uid, String username) implements Principal {

  @Override
  public String getName() {
    return username;
  }
}
