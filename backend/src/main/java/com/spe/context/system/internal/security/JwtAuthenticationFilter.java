package com.spe.context.system.internal.security;

import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.security.BearerTokens;
import com.spe.context.system.api.UserBrief;
import com.spe.context.system.api.UserQueryFacade;
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
 * 解析 Bearer 令牌并写入 SecurityContext（09 D6）。令牌缺失、无效，或**账号已被停用／已不存在**时都不抛异常，
 * 交由 {@link RestAuthenticationEntryPoint} 统一输出 401 Result；角色按 01 §7 转成 {@code ROLE_*} 权限。
 *
 * <p><b>为什么每次请求都要回查账号</b>：JWT 只证明「签发时刻」的身份——签发之后管理员可能停用账号或调整角色，
 * 而 01 SM1/§7 把停用当作**立即生效**的状态（审批链校验、评分单改派都以此为前提）。若只验签名与过期，
 * 已停用账号在令牌 TTL（默认 2h）内继续通行，属安全缺口（03 §16 的 SEC-1，已用真实链路实测确证）。
 *
 * <p>因此这里按 {@code uid} 取一次 {@link UserBrief}：不存在或非 {@code ENABLED} 即视为未认证；角色也以
 * **库中当前值**为准（令牌里的 {@code roles} 只是签发时快照，降权同样要即时生效）。代价是每个带令牌的
 * 请求多一次主键查询（本地库、走主键、buffer pool 命中），换来停用与降权的即时性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String ROLE_PREFIX = "ROLE_";

  private final JwtService jwtService;
  private final UserQueryFacade userQueryFacade;

  /** 门户路径由 {@link SupplierPortalAuthenticationFilter} 处理，内部 JWT 不参与。 */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/api/supplier-portal/");
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
    JwtPayload payload;
    try {
      payload = jwtService.parse(token);
    } catch (JwtException | IllegalArgumentException ex) {
      // 只记原因不记令牌本体（06 §2.8：日志不得出现 Token）
      SecurityContextHolder.clearContext();
      log.debug("令牌无效或已过期：{}", ex.getMessage());
      return;
    }

    UserBrief operator = userQueryFacade.findById(payload.uid()).orElse(null);
    if (operator == null || !operator.enabled()) {
      SecurityContextHolder.clearContext();
      log.debug("令牌对应的账号不存在或已停用：uid={}", payload.uid());
      return;
    }

    List<SimpleGrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority(ROLE_PREFIX + operator.role().name()));
    // principal 带 uid + 账号：Authentication.getName() 仍是账号（Principal.getName()），
    // 而审计与后续的数据可见性过滤能从 principal 直接拿到 uid
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            new AuthenticatedOperator(operator.id(), operator.username()), null, authorities);
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
}
