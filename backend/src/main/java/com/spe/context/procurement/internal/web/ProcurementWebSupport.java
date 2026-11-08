package com.spe.context.procurement.internal.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Controller 共用：从安全上下文取 ADMIN 标志（审批矩阵据此判断是否满足升级审批）。 */
final class ProcurementWebSupport {

  private ProcurementWebSupport() {}

  static boolean isAdmin(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch("ROLE_ADMIN"::equals);
  }
}
