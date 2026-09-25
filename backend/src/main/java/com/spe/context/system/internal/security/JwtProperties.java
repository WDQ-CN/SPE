package com.spe.context.system.internal.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（09 D6）：密钥走环境变量 JWT_SECRET（不入库、不写进配置文件），TTL 默认 2 小时。
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration ttl) {
}
