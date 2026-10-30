package com.spe.context.system.internal.security;

import com.spe.common.security.ApiTokenPrincipal;
import com.spe.context.system.internal.token.ApiTokenService;
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
 * 开放 API 的独立 Token 鉴权（M6 / 05 ADR-8）。
 *
 * <p>只处理 `/open/v1/**`；Bearer 头若不是有效 Token，不会覆盖已有的 JWT 认证，最终由
 * `/open/v1/**` 的 `hasRole('API_TOKEN')` 规则拒绝（JWT 用户 403，无凭证 401）。
 * Token 明文不打印、不写审计；`ApiTokenService.authenticate` 内部负责哈希查找与 `last_used_at`。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String ROLE_API_TOKEN = "ROLE_API_TOKEN";

  private final ApiTokenService apiTokenService;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/open/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (hasBearerPrefix(header)) {
      apiTokenService
          .authenticate(header.substring(BEARER_PREFIX.length()))
          .ifPresent(principal -> setAuthentication(request, principal));
    }
    filterChain.doFilter(request, response);
  }

  private static boolean hasBearerPrefix(String header) {
    return header != null
        && header.length() > BEARER_PREFIX.length()
        && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
  }

  private static void setAuthentication(HttpServletRequest request, ApiTokenPrincipal principal) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(ROLE_API_TOKEN)));
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
