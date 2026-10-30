package com.spe.common.security;

import java.security.Principal;

/**
 * 开放 API Token 的认证身份（M6 / ADR-8）：只带 token id 与名称，**不带用户身份**。
 *
 * <p>放在 common.security 的理由与 {@link AuthenticatedOperator} 相同：零依赖记录类型，
 * system 的 Token 过滤器写入安全上下文，openapi 控制器/日志可读取，不必把 system.internal 外泄。
 */
public record ApiTokenPrincipal(long tokenId, String tokenName) implements Principal {

  @Override
  public String getName() {
    return tokenName;
  }
}
