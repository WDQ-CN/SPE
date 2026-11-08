package com.spe.context.system.internal.security;

import com.spe.common.security.BearerTokens;
import com.spe.common.security.SupplierPortalPrincipal;
import com.spe.context.system.internal.portal.SupplierPortalUser;
import com.spe.context.system.internal.portal.SupplierPortalUserService;
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
 * 供应商门户 JWT 过滤器：只处理 `/api/supplier-portal/**`，与内部 JWT 完全隔离。
 *
 * <p>每次请求回查门户账号与供应商当前状态（停用即时生效），而不是信任令牌快照。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupplierPortalAuthenticationFilter extends OncePerRequestFilter {

  private static final String PREFIX = "/api/supplier-portal/";
  private static final String AUTHORITY = "PORTAL_SUPPLIER";

  private final PortalJwtService portalJwtService;
  private final SupplierPortalUserService portalUserService;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return !uri.startsWith(PREFIX) || uri.startsWith(PREFIX + "auth/login") || uri.startsWith(PREFIX + "auth/refresh");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String token = BearerTokens.extract(request.getHeader(HttpHeaders.AUTHORIZATION));
    if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
      authenticate(token, request);
    }
    filterChain.doFilter(request, response);
  }

  private void authenticate(String token, HttpServletRequest request) {
    PortalJwtPayload payload;
    try {
      payload = portalJwtService.parse(token);
    } catch (JwtException | IllegalArgumentException ex) {
      SecurityContextHolder.clearContext();
      log.debug("门户令牌无效或已过期：{}", ex.getMessage());
      return;
    }
    SupplierPortalUser user = portalUserService.validateForRequest(payload.portalUserId());
    if (user == null
        || user.getSupplierId() != payload.supplierId()
        || !user.getLoginName().equals(payload.loginName())) {
      SecurityContextHolder.clearContext();
      log.debug("门户令牌对应账号不存在/已停用/供应商已停用：pid={}", payload.portalUserId());
      return;
    }
    SupplierPortalPrincipal principal =
        new SupplierPortalPrincipal(user.getId(), user.getSupplierId(), user.getLoginName());
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
