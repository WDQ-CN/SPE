package com.spe.context.system.internal.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 供应商门户 JWT 配置；未设置独立密钥时回落到内部 JWT 密钥，但 issuer/claims 仍独立。 */
@ConfigurationProperties(prefix = "app.portal-jwt")
public record PortalJwtProperties(String secret, Duration ttl, Duration refreshTtl) {}
