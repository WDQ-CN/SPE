package com.spe.context.system.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * JwtAuthenticationFilter 行为锁定：本类是安全链的入口，P0 之前只有人工验证覆盖，
 * 这里把「接受路径」与三种拒绝路径固化为回归测试（纯单测，不装配 Spring 上下文，符合 09 D7）。
 */
class JwtAuthenticationFilterTest {

  private static final String SECRET = "spe-p0-test-secret-0123456789abcdef";
  private static final String AUTHORIZATION = HttpHeaders.AUTHORIZATION;

  private final JwtService jwtService = new JwtService(new JwtProperties(SECRET, Duration.ofHours(2)));
  private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  private static JwtPayload payload() {
    return new JwtPayload(42L, "zhangsan", Set.of("MANAGER", "EVALUATOR"), "PURCHASE");
  }

  private Authentication filterWith(String authorizationHeader) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (authorizationHeader != null) {
      request.addHeader(AUTHORIZATION, authorizationHeader);
    }
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    return SecurityContextHolder.getContext().getAuthentication();
  }

  @Test
  @DisplayName("合法令牌：写入认证，principal 为账号，角色映射为 ROLE_* 权限（01 §7）")
  void validTokenPopulatesSecurityContext() throws Exception {
    String token = jwtService.issue(payload(), Instant.now());

    Authentication authentication = filterWith("Bearer " + token);

    assertThat(authentication).isNotNull();
    assertThat(authentication.getPrincipal()).isEqualTo("zhangsan");
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .containsExactlyInAnyOrder("ROLE_MANAGER", "ROLE_EVALUATOR");
  }

  @Test
  @DisplayName("过期令牌：保持未认证且不抛异常（交由 EntryPoint 输出 401）")
  void expiredTokenLeavesContextEmpty() throws Exception {
    JwtService shortLived = new JwtService(new JwtProperties(SECRET, Duration.ofSeconds(-3600)));
    String token = shortLived.issue(payload(), Instant.now());

    assertThat(filterWith("Bearer " + token)).isNull();
  }

  @Test
  @DisplayName("格式非法的令牌：保持未认证且不抛异常")
  void malformedTokenLeavesContextEmpty() throws Exception {
    assertThat(filterWith("Bearer not-a-jwt")).isNull();
  }

  @Test
  @DisplayName("无 Authorization 头：保持未认证")
  void missingHeaderLeavesContextEmpty() throws Exception {
    assertThat(filterWith(null)).isNull();
  }

  @Test
  @DisplayName("不带 Bearer 前缀的 Authorization 头被忽略")
  void nonBearerHeaderIsIgnored() throws Exception {
    String token = jwtService.issue(payload(), Instant.now());

    assertThat(filterWith("Basic " + token)).isNull();
  }
}
