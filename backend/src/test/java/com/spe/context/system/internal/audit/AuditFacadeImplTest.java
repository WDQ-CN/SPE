package com.spe.context.system.internal.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditTargetType;
import com.spe.common.security.AuthenticatedOperator;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计落库行为锁定（05 §5.5 T6 / 06 §2.8）：
 * 操作人取自令牌 principal、detail 序列化为 JSON、IP 取真实来源地址。
 */
@ExtendWith(MockitoExtension.class)
class AuditFacadeImplTest {

  @Mock private AuditLogMapper auditLogMapper;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @AfterEach
  void clearContexts() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  private AuditFacadeImpl facade() {
    return new AuditFacadeImpl(auditLogMapper, objectMapper);
  }

  private void authenticate() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new AuthenticatedOperator(5L, "admin"), null, List.of()));
  }

  @Test
  @DisplayName("已认证：operator_id 取令牌 uid、operator_name 取账号（免回查用户表）")
  void authenticatedOperatorComesFromToken() {
    authenticate();

    facade().record(AuditAction.USER_STATUS_CHANGE, AuditTargetType.USER, 7L, Map.of("to", "DISABLED"));

    ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogMapper).insert(entry.capture());
    assertThat(entry.getValue().getOperatorId()).isEqualTo(5L);
    assertThat(entry.getValue().getOperatorName()).isEqualTo("admin");
    assertThat(entry.getValue().getAction()).isEqualTo("USER_STATUS_CHANGE");
    assertThat(entry.getValue().getTargetType()).isEqualTo("USER");
    assertThat(entry.getValue().getTargetId()).isEqualTo(7L);
    assertThat(entry.getValue().getDetail()).isEqualTo("{\"to\":\"DISABLED\"}");
  }

  @Test
  @DisplayName("无认证（登录失败路径）：操作人留空，但记下被尝试的账号")
  void anonymousAttemptKeepsOperatorIdNull() {
    facade().recordForOperator(null, "ghost", AuditAction.AUTH_LOGIN_FAILED, null, null, null);

    ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogMapper).insert(entry.capture());
    assertThat(entry.getValue().getOperatorId()).isNull();
    assertThat(entry.getValue().getOperatorName()).isEqualTo("ghost");
    assertThat(entry.getValue().getDetail()).isNull();
    assertThat(entry.getValue().getTargetType()).isNull();
  }

  @Test
  @DisplayName("detail 为空时不写 JSON（列可空，避免存 \"{}\" 这种噪声）")
  void emptyDetailBecomesNull() {
    authenticate();

    facade().record(AuditAction.USER_PASSWORD_RESET, AuditTargetType.USER, 3L, Map.of());

    ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogMapper).insert(entry.capture());
    assertThat(entry.getValue().getDetail()).isNull();
  }

  @Test
  @DisplayName("IP 取当前请求的远程地址（经 Nginx 反代时依赖 forward-headers-strategy 还原）")
  void ipComesFromCurrentRequest() {
    authenticate();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.7");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    facade().record(AuditAction.SETTING_UPDATE, AuditTargetType.SETTING, null, null);

    ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogMapper).insert(entry.capture());
    assertThat(entry.getValue().getIp()).isEqualTo("203.0.113.7");
  }

  @Test
  @DisplayName("无请求上下文（事件监听等非 Web 路径）：IP 留空而不是抛异常")
  void ipIsNullOutsideWebRequest() {
    authenticate();

    facade().record(AuditAction.USER_CREATE, AuditTargetType.USER, 1L, null);

    ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogMapper).insert(entry.capture());
    assertThat(entry.getValue().getIp()).isNull();
  }
}
