package com.spe.context.integration.internal;

import com.spe.common.security.ApiTokenPrincipal;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.security.Hashes;
import com.spe.common.security.SupplierPortalPrincipal;
import com.spe.common.web.BizException;
import com.spe.common.web.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.ObjectMapper;

/**
 * 写请求幂等（docs/erp/42 §3/§6）：仅当请求带 Idempotency-Key 时生效。
 *
 * <p>先占位再执行；成功缓存响应，重复请求原样回放；同键不同内容 2247；处理中的并发请求 3001。
 *
 * <p><b>作用域必须带调用者</b>：只按「方法 + URL + 查询串」建键会让不同用户用同一
 * Idempotency-Key 互相回放响应（跨用户数据泄漏）。因此本过滤器排在 Spring Security 之后
 * （{@link SecurityFilterProperties#DEFAULT_FILTER_ORDER} + 10），从 SecurityContext 取稳定身份；
 * 匿名请求（登录、邀请接受等）不做幂等缓存。
 */
@Component
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10)
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

  private static final String HEADER = "Idempotency-Key";
  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final IdempotencyService idempotencyService;
  private final ObjectMapper objectMapper;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String key = request.getHeader(HEADER);
    return key == null
        || key.isBlank()
        || key.length() > 64
        || !WRITE_METHODS.contains(request.getMethod())
        || (!uri.startsWith("/api/") && !uri.startsWith("/open/v1/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String caller = callerScope();
    if (caller == null) {
      // 登录/邀请接受等匿名写请求不缓存：没有稳定身份，跨用户回放无法安全隔离。
      filterChain.doFilter(request, response);
      return;
    }
    String key = request.getHeader(HEADER).trim();
    String target =
        request.getRequestURI()
            + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    // 目标哈希固定 64 字符，给 scope 列（VARCHAR(200)）留出调用者与方法的余量。
    String scope = caller + " " + request.getMethod() + " " + Hashes.sha256Hex(target);
    CachedBodyRequest cachedRequest = new CachedBodyRequest(request);
    String requestHash = Hashes.sha256Hex(cachedRequest.body());

    IdempotencyRecord stored;
    try {
      stored = idempotencyService.begin(scope, key, requestHash);
    } catch (BizException ex) {
      writeError(response, ex);
      return;
    }
    if (stored != null) {
      response.setStatus(stored.getResponseStatus());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.getWriter().write(stored.getResponseBody() == null ? "" : stored.getResponseBody());
      return;
    }

    ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
    try {
      filterChain.doFilter(cachedRequest, wrappedResponse);
      int status = wrappedResponse.getStatus();
      if (status >= 200 && status < 300) {
        idempotencyService.complete(
            scope,
            key,
            status,
            new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8));
      } else {
        idempotencyService.abort(scope, key);
      }
    } catch (RuntimeException ex) {
      idempotencyService.abort(scope, key);
      throw ex;
    } finally {
      wrappedResponse.copyBodyToResponse();
    }
  }

  private void writeError(HttpServletResponse response, BizException ex) throws IOException {
    response.setStatus(ex.errorCode().httpStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            objectMapper.writeValueAsString(
                Result.fail(ex.errorCode().code(), ex.getMessage())));
  }

  /** 调用者的稳定作用域前缀；匿名或未认证返回 null。 */
  private static String callerScope() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return null;
    }
    Object principal = authentication.getPrincipal();
    String authorityHash = Hashes.sha256Hex(authorities(authentication));
    if (principal instanceof AuthenticatedOperator operator) {
      return "u:" + operator.uid() + "#" + authorityHash;
    }
    if (principal instanceof SupplierPortalPrincipal portal) {
      return "p:" + portal.portalUserId() + "#" + authorityHash;
    }
    if (principal instanceof ApiTokenPrincipal token) {
      return "t:" + token.tokenId() + "#" + authorityHash;
    }
    return "n:" + authentication.getName() + "#" + authorityHash;
  }

  /** 权限集纳入作用域：降权后不能靠旧幂等键回放此前缓存的 2xx 响应。 */
  private static String authorities(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .sorted()
        .collect(Collectors.joining(","));
  }

  /** 预读请求体并允许下游重复读取。 */
  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request) throws IOException {
      super(request);
      this.body = request.getInputStream().readAllBytes();
    }

    private byte[] body() {
      return body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          // 同步读取，无需回调
        }

        @Override
        public int read() {
          return input.read();
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
