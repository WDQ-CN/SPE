package com.spe.context.system.internal.portal;

import com.spe.context.system.api.SupplierPortalUserFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link SupplierPortalUserFacade} 实现：调用方已完成供应商校验。 */
@Component
@RequiredArgsConstructor
public class SupplierPortalUserFacadeImpl implements SupplierPortalUserFacade {

  private final SupplierPortalUserService userService;

  @Override
  public long create(long supplierId, String loginName, String rawPassword) {
    return userService.create(supplierId, loginName, rawPassword).id();
  }
}
