package com.spe.context.system.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserBrief;
import com.spe.context.system.api.UserQueryFacade;
import com.spe.context.system.api.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * 这里把「接受路径」、拒绝路径与**账号状态类拒绝**固化为回归测试（纯单测，不装配 Spring 上下文，符合 09 D7）。
 *
 * <p>后三条用例对应 03 §16 的 SEC-1：令牌不代表「现在」的身份，停用与降权必须即时生效。
 */
class JwtAuthenticationFilterTest {

  private static final String SECRET = "spe-p0-test-secret-0123456789abcdef";
  private static final String AUTHORIZATION = HttpHeaders.AUTHORIZATION;
  private static final long UID = 42L;

  private final JwtService jwtService = new JwtService(new JwtProperties(SECRET, Duration.ofHours(2)));
  private final UserQueryFacade userQueryFacade = mock(UserQueryFacade.class);
  private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, userQueryFacade);

  @BeforeEach
  void operatorIsEnabled() {
    when(userQueryFacade.findById(UID)).thenReturn(Optional.of(brief(UserStatus.ENABLED, Role.MANAGER)));
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  private static UserBrief brief(UserStatus status, Role role) {
    return new UserBrief(UID, "zhangsan", "张三", role, Department.PURCHASE, status);
  }

  private static JwtPayload payload() {
    return new JwtPayload(UID, "zhangsan", Set.of("MANAGER"), "PURCHASE");
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
  @DisplayName("合法令牌：写入认证，principal 携带账号与 uid，角色映射为 ROLE_* 权限（01 §7）")
  void validTokenPopulatesSecurityContext() throws Exception {
    String token = jwtService.issue(payload(), Instant.now());

    Authentication authentication = filterWith("Bearer " + token);

    assertThat(authentication).isNotNull();
    // principal 是 AuthenticatedOperator：getName() 仍是账号（P0 的既有语义不能变），
    // 同时把 uid 带出来供审计（audit_log.operator_id）与 P3 的数据可见性过滤使用
    assertThat(authentication.getName()).isEqualTo("zhangsan");
    assertThat(authentication.getPrincipal()).isEqualTo(new AuthenticatedOperator(42L, "zhangsan"));
    assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_MANAGER");
  }

  @Test
  @DisplayName("账号已停用：令牌立即作废（不写认证，由 EntryPoint 输出 401）——停用不能等 TTL 到期")
  void disabledAccountIsRejected() throws Exception {
    when(userQueryFacade.findById(UID))
        .thenReturn(Optional.of(brief(UserStatus.DISABLED, Role.MANAGER)));
    String token = jwtService.issue(payload(), Instant.now());

    assertThat(filterWith("Bearer " + token)).isNull();
  }

  @Test
  @DisplayName("账号已不存在（被删或 uid 伪造）：令牌作废——自签令牌不能凭签名通过")
  void missingAccountIsRejected() throws Exception {
    when(userQueryFacade.findById(UID)).thenReturn(Optional.empty());
    String token = jwtService.issue(payload(), Instant.now());

    assertThat(filterWith("Bearer " + token)).isNull();
  }

  @Test
  @DisplayName("权限以库中当前角色为准：令牌里是 ADMIN、库里已降为 VIEWER → 只给 ROLE_VIEWER")
  void roleComesFromDatabaseNotFromToken() throws Exception {
    when(userQueryFacade.findById(UID))
        .thenReturn(Optional.of(brief(UserStatus.ENABLED, Role.VIEWER)));
    JwtPayload stale = new JwtPayload(UID, "zhangsan", Set.of("ADMIN"), "PURCHASE");
    String token = jwtService.issue(stale, Instant.now());

    Authentication authentication = filterWith("Bearer " + token);

    assertThat(authentication).isNotNull();
    assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_VIEWER");
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

  @Test
  @DisplayName("Bearer 前缀大小写不敏感（RFC 6750）")
  void bearerPrefixIsCaseInsensitive() throws Exception {
    String token = jwtService.issue(payload(), Instant.now());

    assertThat(filterWith("bearer " + token)).isNotNull();
    SecurityContextHolder.clearContext();
    assertThat(filterWith("BEARER " + token)).isNotNull();
    SecurityContextHolder.clearContext();
    assertThat(filterWith("BeArEr " + token)).isNotNull();
  }

  @Test
  @DisplayName("只有 Bearer 前缀而没有令牌：保持未认证")
  void bearerPrefixWithoutTokenLeavesContextEmpty() throws Exception {
    assertThat(filterWith("Bearer ")).isNull();
  }
}
