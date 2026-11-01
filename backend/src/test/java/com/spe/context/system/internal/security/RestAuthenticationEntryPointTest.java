package com.spe.context.system.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.spe.common.web.ErrorCode;
import com.spe.common.web.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.ObjectMapper;

/** 未认证响应锁定（09 D6、出口判据 2）：401 + Result(1xxx) + RFC 7235 的 Bearer challenge。 */
class RestAuthenticationEntryPointTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestAuthenticationEntryPoint entryPoint = new RestAuthenticationEntryPoint(objectMapper);

  @Test
  @DisplayName("输出 401、Result(1001)、WWW-Authenticate: Bearer")
  void writesUnauthorizedResultWithChallenge() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(
        new MockHttpServletRequest(), response, new InsufficientAuthenticationException("无令牌"));

    assertThat(response.getStatus()).isEqualTo(ErrorCode.UNAUTHORIZED.httpStatus().value());
    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    assertThat(response.getContentType()).contains("application/json");

    Result<?> body = objectMapper.readValue(response.getContentAsByteArray(), Result.class);
    assertThat(body.code()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
    assertThat(body.message()).isEqualTo(ErrorCode.UNAUTHORIZED.message());
    assertThat(body.data()).isNull();
  }
}
