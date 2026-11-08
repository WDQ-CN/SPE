package com.spe.context.supplier.internal;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.SupplierPortalUserFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 供应商门户账号创建：本上下文校验供应商，再经 system.api 门面写门户账号。 */
@Service
@RequiredArgsConstructor
public class SupplierPortalAccountService {

  private final SupplierQueryFacade supplierQueryFacade;
  private final SupplierPortalUserFacade portalUserFacade;

  @Transactional
  public long create(long supplierId, String loginName, String rawPassword) {
    SupplierBrief supplier =
        supplierQueryFacade
            .findById(supplierId)
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_NOT_FOUND));
    if (!supplier.enabled()) {
      throw new BizException(ErrorCode.SUPPLIER_PORTAL_FORBIDDEN, "供应商已停用，不能创建门户账号");
    }
    return portalUserFacade.create(supplierId, loginName, rawPassword);
  }
}
