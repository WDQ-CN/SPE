package com.spe.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * 全局异常处理器的可判定分支（09 D8）。校验失败分支需要 {@code MethodParameter} + {@code BindingResult}
 * 才构造得出，按 09 WP4 的安排在收口阶段用 standalone MockMvc 覆盖，此处不勉强。
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  @DisplayName("业务异常：按错误码携带的 HTTP 状态与文案返回")
  void bizExceptionCarriesCodeAndStatus() {
    var response = handler.handleBizException(new BizException(ErrorCode.CONCURRENT_CONFLICT));

    assertThat(response.getStatusCode()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT.httpStatus());
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT.code());
    assertThat(response.getBody().message()).isEqualTo(ErrorCode.CONCURRENT_CONFLICT.message());
  }

  @Test
  @DisplayName("业务异常：自定义文案优先于错误码默认文案")
  void bizExceptionKeepsCustomMessage() {
    var response =
        handler.handleBizException(new BizException(ErrorCode.BAD_REQUEST, "数量必须为正"));

    assertThat(response.getStatusCode()).isEqualTo(ErrorCode.BAD_REQUEST.httpStatus());
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.BAD_REQUEST.code());
    assertThat(response.getBody().message()).isEqualTo("数量必须为正");
  }

  @Test
  @DisplayName("越权异常：403 + 4xxx")
  void accessDeniedMapsToForbidden() {
    var response = handler.handleAccessDenied(new AccessDeniedException("越权"));

    assertThat(response.getStatusCode()).isEqualTo(ErrorCode.FORBIDDEN.httpStatus());
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.FORBIDDEN.code());
  }

  @Test
  @DisplayName("兜底异常：500 + 5xxx，且响应体不泄漏内部异常信息")
  void unexpectedMapsToInternalErrorWithoutLeakingDetails() {
    var request = new MockHttpServletRequest("GET", "/api/anything");

    var response = handler.handleUnexpected(new IllegalStateException("内部敏感细节"), request);

    assertThat(response.getStatusCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.httpStatus());
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.code());
    assertThat(response.getBody().message()).isEqualTo(ErrorCode.INTERNAL_ERROR.message());
    assertThat(response.getBody().message()).doesNotContain("内部敏感细节");
  }
}
