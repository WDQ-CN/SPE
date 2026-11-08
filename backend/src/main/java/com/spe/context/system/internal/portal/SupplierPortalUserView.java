package com.spe.context.system.internal.portal;

/** 门户账号视图（不含口令哈希）。 */
public record SupplierPortalUserView(
    long id,
    long supplierId,
    String supplierCode,
    String supplierName,
    String loginName,
    SupplierPortalUserStatus status) {}
