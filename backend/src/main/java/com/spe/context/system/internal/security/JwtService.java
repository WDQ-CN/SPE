package com.spe.context.system.internal.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;

/**
 * JWT 签发与校验（09 D6）：HS256，claims 契约见 {@link JwtPayload}，默认 TTL 2 小时。
 *
 * <p>不依赖 Spring 容器，便于单元测试；密钥缺失或强度不足时启动即失败，避免带弱密钥上线。
 */
public class JwtService {

  private static final Duration DEFAULT_TTL = Duration.ofHours(2);
  private static final int MIN_SECRET_BYTES = 32;
  private static final String CLAIM_UID = "uid";
  private static final String CLAIM_ROLES = "roles";
  private static final String CLAIM_DEPARTMENT = "dept";

  private final SecretKey key;
  private final Duration ttl;

  public JwtService(JwtProperties properties) {
    String secret = properties.secret();
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("缺少 JWT 密钥：请通过环境变量 JWT_SECRET 提供，长度不少于 32 字节");
    }
    byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    if (secretBytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "JWT 密钥过短：HS256 要求至少 " + MIN_SECRET_BYTES + " 字节，当前 " + secretBytes.length + " 字节");
    }
    this.key = Keys.hmacShaKeyFor(secretBytes);
    this.ttl = properties.ttl() == null ? DEFAULT_TTL : properties.ttl();
  }

  public Duration ttl() {
    return ttl;
  }

  /** 签发：调用方负责传入签发时刻，便于测试与审计。 */
  public String issue(JwtPayload payload, Instant issuedAt) {
    return Jwts.builder()
        .subject(payload.username())
        .claim(CLAIM_UID, payload.uid())
        .claim(CLAIM_ROLES, List.copyOf(payload.roles()))
        .claim(CLAIM_DEPARTMENT, payload.department())
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(issuedAt.plus(ttl)))
        .signWith(key)
        .compact();
  }

  /**
   * 解析并校验签名与有效期。
   *
   * @throws io.jsonwebtoken.JwtException 签名不符、已过期或格式非法
   */
  public JwtPayload parse(String token) {
    Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    return new JwtPayload(readUid(claims), claims.getSubject(), readRoles(claims), readDepartment(claims));
  }

  private static long readUid(Claims claims) {
    Object value = claims.get(CLAIM_UID);
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private static Set<String> readRoles(Claims claims) {
    Object value = claims.get(CLAIM_ROLES);
    if (value instanceof Collection<?> collection) {
      return collection.stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    }
    return Set.of();
  }

  private static String readDepartment(Claims claims) {
    Object value = claims.get(CLAIM_DEPARTMENT);
    return value == null ? null : String.valueOf(value);
  }
}
