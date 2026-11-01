package com.spe.context.system.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.spe.common.web.ErrorCode;
import com.spe.common.web.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

/** 越权响应锁定（09 D6）：403 + Result(4xxx)，且不携带 401 专属的 challenge。 */
class RestAccessDeniedHandlerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestAccessDeniedHandler handler = new RestAccessDeniedHandler(objectMapper);

  @Test
  @DisplayName("输出 403、Result(4001)，且不带 WWW-Authenticate（challenge 只属 401）")
  void writesForbiddenResultWithoutChallenge() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("越权"));

    assertThat(response.getStatus()).isEqualTo(ErrorCode.FORBIDDEN.httpStatus().value());
    assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();

    Result<?> body = objectMapper.readValue(response.getContentAsByteArray(), Result.class);
    assertThat(body.code()).isEqualTo(ErrorCode.FORBIDDEN.code());
    assertThat(body.message()).isEqualTo(ErrorCode.FORBIDDEN.message());
  }
}
