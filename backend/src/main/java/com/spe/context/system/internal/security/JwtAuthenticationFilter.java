package com.spe.context.system.internal.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 解析 Bearer 令牌并写入 SecurityContext（09 D6）。令牌缺失或无效时不抛异常，
 * 交由 {@link RestAuthenticationEntryPoint} 统一输出 401 Result；角色按 01 §7 转成 {@code ROLE_*} 权限。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String ROLE_PREFIX = "ROLE_";

  private final JwtService jwtService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (hasBearerPrefix(header) && SecurityContextHolder.getContext().getAuthentication() == null) {
      authenticate(header.substring(BEARER_PREFIX.length()), request);
    }
    filterChain.doFilter(request, response);
  }

  /** RFC 6750 规定 auth-scheme 大小写不敏感；长度比较同时排除只有「Bearer 」而无可校验令牌的头。 */
  private static boolean hasBearerPrefix(String header) {
    return header != null
        && header.length() > BEARER_PREFIX.length()
        && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
  }

  private void authenticate(String token, HttpServletRequest request) {
    try {
      JwtPayload payload = jwtService.parse(token);
      List<SimpleGrantedAuthority> authorities =
          payload.roles().stream().map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role)).toList();
      UsernamePasswordAuthenticationToken authentication =
          new UsernamePasswordAuthenticationToken(payload.username(), null, authorities);
      authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(authentication);
    } catch (JwtException | IllegalArgumentException ex) {
      // 只记原因不记令牌本体（06 §2.8：日志不得出现 Token）
      SecurityContextHolder.clearContext();
      log.debug("令牌无效或已过期：{}", ex.getMessage());
    }
  }
}
