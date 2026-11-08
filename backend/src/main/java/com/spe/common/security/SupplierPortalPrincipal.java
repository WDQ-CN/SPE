package com.spe.common.security;

import java.security.Principal;

/**
 * 供应商门户操作人（与内部 {@link AuthenticatedOperator} 分离）。
 *
 * <p>`supplierId` 是数据边界，服务层必须用它做归属过滤；门户令牌不得映射为内部用户。
 */
public record SupplierPortalPrincipal(long portalUserId, long supplierId, String loginName)
    implements Principal {

  @Override
  public String getName() {
    return loginName;
  }
}
