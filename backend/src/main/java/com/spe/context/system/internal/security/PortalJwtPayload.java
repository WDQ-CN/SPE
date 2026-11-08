package com.spe.context.system.internal.security;

/** 门户 JWT 载荷：门户账号、供应商与登录名。 */
public record PortalJwtPayload(long portalUserId, long supplierId, String loginName) {}
