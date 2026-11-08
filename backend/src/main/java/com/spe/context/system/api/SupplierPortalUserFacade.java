package com.spe.context.system.api;

/** 供应商门户账号创建门面（由 supplier 上下文在完成供应商校验后调用）。 */
public interface SupplierPortalUserFacade {

  long create(long supplierId, String loginName, String rawPassword);
}
