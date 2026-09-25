package com.spe.context.system.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JwtService 行为锁定（09 D6 / WP4）：签发、解析、过期拒绝、篡改拒绝、密钥强度校验。 */
class JwtServiceTest {

  private static final String SECRET = "spe-p0-test-secret-0123456789abcdef";
  private static final Duration TTL = Duration.ofHours(2);

  private static JwtService service(Duration ttl) {
    return new JwtService(new JwtProperties(SECRET, ttl));
  }

  private static JwtPayload payload() {
    return new JwtPayload(42L, "zhangsan", Set.of("MANAGER", "EVALUATOR"), "PURCHASE");
  }

  @Test
  @DisplayName("签发后可解析出同一载荷（sub/uid/roles/dept 契约）")
  void issueThenParseKeepsClaims() {
    JwtService jwtService = service(TTL);
    String token = jwtService.issue(payload(), Instant.now());

    JwtPayload parsed = jwtService.parse(token);

    assertThat(parsed.uid()).isEqualTo(42L);
    assertThat(parsed.username()).isEqualTo("zhangsan");
    assertThat(parsed.roles()).containsExactlyInAnyOrder("MANAGER", "EVALUATOR");
    assertThat(parsed.department()).isEqualTo("PURCHASE");
  }

  @Test
  @DisplayName("过期超出 60s 时钟容差的令牌被拒绝")
  void tokenExpiredBeyondClockSkewIsRejected() {
    JwtService jwtService = service(Duration.ofSeconds(-120));
    String token = jwtService.issue(payload(), Instant.now());

    assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
  }

  @Test
  @DisplayName("过期但在 60s 时钟容差内的令牌仍被接受")
  void tokenExpiredWithinClockSkewIsAccepted() {
    JwtService jwtService = service(Duration.ofSeconds(-30));
    String token = jwtService.issue(payload(), Instant.now());

    assertThat(jwtService.parse(token).username()).isEqualTo("zhangsan");
  }

  @Test
  @DisplayName("缺少 sub 的令牌被拒绝（账号是数据可见性过滤的依据）")
  void tokenWithoutSubjectIsRejected() {
    SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    String token =
        Jwts.builder()
            .claim("uid", 1L)
            .issuedAt(new Date())
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(key)
            .compact();

    assertThatThrownBy(() -> service(TTL).parse(token)).isInstanceOf(JwtException.class);
  }

  @Test
  @DisplayName("篡改令牌被拒绝")
  void tamperedTokenIsRejected() {
    JwtService jwtService = service(TTL);
    String token = jwtService.issue(payload(), Instant.now());
    char last = token.charAt(token.length() - 1);
    String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

    assertThatThrownBy(() -> jwtService.parse(tampered)).isInstanceOf(JwtException.class);
  }

  @Test
  @DisplayName("TTL 缺省为 2 小时")
  void ttlFallsBackToTwoHours() {
    assertThat(service(null).ttl()).isEqualTo(TTL);
    assertThat(service(Duration.ofMinutes(30)).ttl()).isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  @DisplayName("密钥缺失时启动失败")
  void missingSecretFailsFast() {
    assertThatThrownBy(() -> new JwtService(new JwtProperties("  ", TTL)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET");
  }

  @Test
  @DisplayName("密钥强度不足时启动失败（HS256 要求 32 字节）")
  void weakSecretFailsFast() {
    assertThatThrownBy(() -> new JwtService(new JwtProperties("too-short-secret", TTL)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32");
  }

  @Test
  @DisplayName("签发算法恒为 HS256，不随密钥长度漂移")
  void signingAlgorithmIsAlwaysHs256() {
    // 未显式指定算法时，三段拼接的 111 字节密钥会被 jjwt 选成 HS512
    JwtService jwtService = new JwtService(new JwtProperties(SECRET.repeat(3), TTL));

    String token = jwtService.issue(payload(), Instant.now());
    String header =
        new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);

    assertThat(header).contains("\"alg\":\"HS256\"");
    assertThat(jwtService.parse(token).username()).isEqualTo("zhangsan");
  }
}
