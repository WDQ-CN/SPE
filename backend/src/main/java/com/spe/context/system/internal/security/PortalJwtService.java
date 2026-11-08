package com.spe.context.system.internal.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

/**
 * 供应商门户 JWT（独立 issuer/claims；密钥优先 PORTAL_JWT_SECRET，缺省回落内部密钥）。
 *
 * <p>与内部 {@link JwtService} 的令牌不能互换：本服务要求 {@code typ=portal} 与
 * {@code iss=spe-supplier-portal}，内部过滤器也会跳过门户路径。
 */
public class PortalJwtService {

  private static final String ISSUER = "spe-supplier-portal";
  private static final String CLAIM_TYPE = "typ";
  private static final String CLAIM_PORTAL_USER = "pid";
  private static final String CLAIM_SUPPLIER = "sid";
  private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
  private static final int MIN_SECRET_BYTES = 32;
  private static final long CLOCK_SKEW_SECONDS = 60;

  private final SecretKey key;
  private final Duration ttl;

  public PortalJwtService(PortalJwtProperties properties, JwtProperties internal) {
    String secret =
        properties.secret() == null || properties.secret().isBlank()
            ? internal.secret()
            : properties.secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "缺少门户 JWT 密钥：请通过 PORTAL_JWT_SECRET（或 JWT_SECRET）提供，长度不少于 32 字节");
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException("门户 JWT 密钥过短：至少需要 " + MIN_SECRET_BYTES + " 字节");
    }
    this.key = Keys.hmacShaKeyFor(bytes);
    this.ttl = properties.ttl() == null ? DEFAULT_TTL : properties.ttl();
  }

  public Duration ttl() {
    return ttl;
  }

  public String issue(PortalJwtPayload payload, Instant issuedAt) {
    return Jwts.builder()
        .subject(payload.loginName())
        .issuer(ISSUER)
        .claim(CLAIM_TYPE, "portal")
        .claim(CLAIM_PORTAL_USER, payload.portalUserId())
        .claim(CLAIM_SUPPLIER, payload.supplierId())
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(issuedAt.plus(ttl)))
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }

  public PortalJwtPayload parse(String token) {
    Claims claims =
        Jwts.parser()
            .verifyWith(key)
            .clockSkewSeconds(CLOCK_SKEW_SECONDS)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    if (!"portal".equals(claims.get(CLAIM_TYPE)) || !ISSUER.equals(claims.getIssuer())) {
      throw new MalformedJwtException("不是供应商门户令牌");
    }
    String loginName = claims.getSubject();
    Object portalUserId = claims.get(CLAIM_PORTAL_USER);
    Object supplierId = claims.get(CLAIM_SUPPLIER);
    if (loginName == null
        || !(portalUserId instanceof Number pid)
        || !(supplierId instanceof Number sid)) {
      throw new MalformedJwtException("门户令牌缺少必要声明");
    }
    return new PortalJwtPayload(pid.longValue(), sid.longValue(), loginName);
  }
}
