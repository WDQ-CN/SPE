package com.spe.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** TraceId 只接受日志安全字符，非法输入回退为服务端生成值。 */
class TraceIdFilterTest {

  private final TraceIdFilter filter = new TraceIdFilter();

  @Test
  @DisplayName("合法 X-Trace-Id 原样回写")
  void keepsSafeIncomingTraceId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TraceIdFilter.HEADER, "trace-gov-check");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("trace-gov-check");
  }

  @Test
  @DisplayName("含控制字符或空白的 X-Trace-Id 不进入响应头")
  void replacesUnsafeIncomingTraceId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(TraceIdFilter.HEADER, "bad trace\nid");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getHeader(TraceIdFilter.HEADER))
        .isNotEqualTo("bad trace\nid")
        .matches("[0-9a-f]{32}");
  }
}
