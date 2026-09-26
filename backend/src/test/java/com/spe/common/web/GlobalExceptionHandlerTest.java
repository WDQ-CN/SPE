package com.spe.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * 全局异常处理器的可判定分支（09 D8）。
 *
 * <p>两条校验分支（请求体校验、方法级校验）由 {@link GlobalExceptionHandlerValidationTest} 用
 * standalone MockMvc 覆盖——那里真的走了一遍参数绑定与 JSR-303，比在本类手工构造
 * {@code ConstraintViolationException} 更贴近真实链路。本类原有一个手工构造的同类分支，
 * 在测试体系审查时判为**重复覆盖**并删除（两者断言的 HTTP 状态、错误码、文案完全相同）。
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
