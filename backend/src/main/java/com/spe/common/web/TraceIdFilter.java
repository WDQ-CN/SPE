package com.spe.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 全链路 traceId：接受调用方 X-Trace-Id 或生成，回写响应头与 MDC，审计/日志共用。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Trace-Id";
  public static final String MDC_KEY = "traceId";

  /** 只接受日志安全的短标识，防止调用方把控制字符/换行带进日志与审计。 */
  private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String normalized = incoming == null ? null : incoming.trim();
    String traceId =
        normalized != null && SAFE_TRACE_ID.matcher(normalized).matches()
            ? normalized
            : UUID.randomUUID().toString().replace("-", "");
    MDC.put(MDC_KEY, traceId);
    response.setHeader(HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  public static String current() {
    return MDC.get(MDC_KEY);
  }
}
