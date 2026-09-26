package com.spe.context.system.internal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.internal.security.AuthenticatedOperator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 越权留痕（05 §9 Q8 / 10 §E15）：只记「已登录但被拒」，匿名请求的 401 不记。
 */
@ExtendWith(MockitoExtension.class)
class AccessDeniedAuditListenerTest {

  @Mock private AuditFacade auditFacade;

  @AfterEach
  void resetRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static AuthorizationDeniedEvent<?> deniedEvent(Authentication authentication) {
    @SuppressWarnings("unchecked")
    AuthorizationDeniedEvent<Object> event = mock(AuthorizationDeniedEvent.class);
    when(event.getAuthentication()).thenReturn(() -> authentication);
    return event;
  }

  @Test
  @DisplayName("已登录用户被拒：记 ACCESS_DENIED，detail 带方法与路径（不含请求体）")
  void authenticatedDenialIsAudited() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    Authentication authentication =
        new UsernamePasswordAuthenticationToken(
            new AuthenticatedOperator(9L, "manager"), null, List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));

    new AccessDeniedAuditListener(auditFacade).onAuthorizationDenied(deniedEvent(authentication));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditFacade).record(eq(AuditAction.ACCESS_DENIED), any(), eq(null), detail.capture());
    assertThat(detail.getValue()).containsEntry("method", "GET").containsEntry("uri", "/api/users");
  }

  @Test
  @DisplayName("匿名请求（未带令牌的 401 路径）：不记审计，避免用无操作人的噪声污染日志")
  void anonymousDenialIsNotAudited() {
    AnonymousAuthenticationToken anonymous =
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

    new AccessDeniedAuditListener(auditFacade).onAuthorizationDenied(deniedEvent(anonymous));

    verify(auditFacade, never()).record(any(), any(), any(), any());
  }

  @Test
  @DisplayName("未认证的空上下文：不记审计也不抛异常")
  void nullAuthenticationIsIgnored() {
    new AccessDeniedAuditListener(auditFacade).onAuthorizationDenied(deniedEvent(null));

    verify(auditFacade, never()).record(any(), any(), any(), any());
  }

  @Test
  @DisplayName("越权审计的目标类型留空：动作本身已表明对象，强行填 SETTING/USER 反而误导")
  void accessDeniedHasNoTargetType() {
    Authentication authentication =
        new UsernamePasswordAuthenticationToken(new AuthenticatedOperator(9L, "manager"), null, List.of());

    new AccessDeniedAuditListener(auditFacade).onAuthorizationDenied(deniedEvent(authentication));

    verify(auditFacade).record(eq(AuditAction.ACCESS_DENIED), eq(null), eq(null), any());
  }
}
