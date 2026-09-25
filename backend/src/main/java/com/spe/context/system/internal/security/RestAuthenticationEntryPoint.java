package com.spe.context.system.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spe.common.web.ErrorCode;
import com.spe.common.web.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** 未认证访问受保护资源：输出 401 + Result（1xxx 段），替代 Spring Security 默认的空响应（09 D6、出口判据 2）。 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final String BEARER_CHALLENGE = "Bearer";

  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
      throws IOException {
    response.setStatus(ErrorCode.UNAUTHORIZED.httpStatus().value());
    // RFC 7235 要求 401 携带 challenge，客户端据此得知应使用哪种凭证（RFC 6750 的 Bearer）。
    // challenge 只属于 401，403 不设置（见 RestAccessDeniedHandler）。
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(response.getWriter(), Result.fail(ErrorCode.UNAUTHORIZED));
  }
}
