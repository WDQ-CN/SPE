package com.spe.context.system.internal.portal;

import com.spe.common.web.BizException;
import com.spe.common.web.ClientIpResolver;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.internal.security.LoginAttemptLimiter;
import com.spe.context.system.internal.security.PortalJwtPayload;
import com.spe.context.system.internal.security.PortalJwtProperties;
import com.spe.context.system.internal.security.PortalJwtService;
import com.spe.context.system.internal.token.TokenHasher;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 供应商门户登录/刷新/登出（独立于内部 AuthService）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierPortalAuthService {

  private static final Duration DEFAULT_REFRESH_TTL = Duration.ofDays(7);

  /** 固定 cost 的无效哈希：账号不存在时也跑一次 BCrypt，避免用响应时间枚举账号。 */
  private static final String DUMMY_PASSWORD_HASH =
      "$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm";

  private final SupplierPortalUserMapper userMapper;
  private final SupplierPortalRefreshTokenMapper refreshTokenMapper;
  private final PasswordEncoder passwordEncoder;
  private final PortalJwtService portalJwtService;
  private final PortalJwtProperties portalJwtProperties;
  private final LoginAttemptLimiter loginAttemptLimiter;
  private final AuditFacade auditFacade;

  public record PortalLoginResult(
      String accessToken, String refreshToken, long expiresIn, SupplierPortalUserView user) {}

  public PortalLoginResult login(String loginName, String rawPassword) {
    String normalizedLogin = loginName == null ? "" : loginName.trim();
    String limiterKey = "portal:" + normalizedLogin;
    String clientIp = ClientIpResolver.current();
    if (loginAttemptLimiter.isLocked(limiterKey, clientIp)) {
      loginAttemptLimiter.recordRejection();
      throw new BizException(ErrorCode.LOGIN_TOO_MANY_ATTEMPTS);
    }
    SupplierPortalUser user = userMapper.findByLoginNameForAuth(normalizedLogin).orElse(null);
    String hash = user == null ? DUMMY_PASSWORD_HASH : user.getPasswordHash();
    // 先算匹配再判 user：避免短路导致未知账号不跑 BCrypt，留下账号枚举的时序信号。
    boolean passwordMatches = passwordEncoder.matches(rawPassword, hash);
    if (user == null || !passwordMatches) {
      throw fail(normalizedLogin, limiterKey, clientIp);
    }
    if (user.getStatus() != SupplierPortalUserStatus.ACTIVE) {
      throw fail(normalizedLogin, limiterKey, clientIp);
    }
    loginAttemptLimiter.reset(limiterKey, clientIp);
    userMapper.updateLastLogin(user.getId());
    SupplierPortalUserView view = toView(user);
    String accessToken =
        portalJwtService.issue(
            new PortalJwtPayload(user.getId(), user.getSupplierId(), user.getLoginName()),
            java.time.Instant.now());
    String refreshToken = createRefreshToken(user.getId());
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("supplierId", user.getSupplierId());
    detail.put("loginName", user.getLoginName());
    auditFacade.recordForOperator(
        user.getId(),
        "portal:" + user.getLoginName(),
        AuditAction.SUPPLIER_PORTAL_LOGIN_SUCCESS,
        AuditTargetType.SUPPLIER_PORTAL_USER,
        user.getId(),
        detail);
    log.info("门户账号 {} 登录成功（供应商 {}）", user.getLoginName(), user.getSupplierId());
    return new PortalLoginResult(
        accessToken, refreshToken, portalJwtService.ttl().getSeconds(), view);
  }

  @Transactional
  public PortalLoginResult refresh(String rawRefreshToken) {
    String hash = TokenHasher.hash(rawRefreshToken);
    SupplierPortalRefreshToken stored =
        refreshTokenMapper
            .findActiveByHash(hash)
            .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期"));
    SupplierPortalUser user =
        userMapper
            .findById(stored.getPortalUserId())
            .orElseThrow(() -> new BizException(ErrorCode.SUPPLIER_PORTAL_USER_NOT_FOUND));
    if (user.getStatus() != SupplierPortalUserStatus.ACTIVE) {
      throw new BizException(ErrorCode.SUPPLIER_PORTAL_FORBIDDEN, "门户账号已停用");
    }
    // 守卫撤销：并发刷新时只有一个请求能拿到 1，另一个按无效令牌拒绝，避免同一刷新令牌换出两对令牌。
    if (refreshTokenMapper.revoke(stored.getId()) == 0) {
      throw new BizException(ErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期");
    }
    String accessToken =
        portalJwtService.issue(
            new PortalJwtPayload(user.getId(), user.getSupplierId(), user.getLoginName()),
            java.time.Instant.now());
    String refreshToken = createRefreshToken(user.getId());
    return new PortalLoginResult(
        accessToken, refreshToken, portalJwtService.ttl().getSeconds(), toView(user));
  }

  @Transactional
  public void logout(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      return;
    }
    refreshTokenMapper
        .findActiveByHash(TokenHasher.hash(rawRefreshToken))
        .ifPresent(token -> refreshTokenMapper.revoke(token.getId()));
  }

  private String createRefreshToken(long portalUserId) {
    String raw = TokenHasher.generateToken();
    Duration ttl =
        portalJwtProperties.refreshTtl() == null
            ? DEFAULT_REFRESH_TTL
            : portalJwtProperties.refreshTtl();
    SupplierPortalRefreshToken token = new SupplierPortalRefreshToken();
    token.setPortalUserId(portalUserId);
    token.setTokenHash(TokenHasher.hash(raw));
    token.setExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).plus(ttl));
    refreshTokenMapper.insert(token);
    return raw;
  }

  private BizException fail(String loginName, String limiterKey, String clientIp) {
    boolean locked = loginAttemptLimiter.recordFailure(limiterKey, clientIp);
    try {
      auditFacade.recordForOperator(
          null,
          "portal:" + loginName,
          AuditAction.SUPPLIER_PORTAL_LOGIN_FAILED,
          null,
          null,
          null);
    } catch (RuntimeException auditFailure) {
      log.error("门户登录失败留痕写入失败：账号={} ip={}", loginName, clientIp, auditFailure);
    }
    if (locked) {
      log.warn("门户登录失败次数达到上限：账号={} ip={}", loginName, clientIp);
    }
    return new BizException(ErrorCode.LOGIN_FAILED);
  }

  private SupplierPortalUserView toView(SupplierPortalUser user) {
    return new SupplierPortalUserView(
        user.getId(), user.getSupplierId(), null, null, user.getLoginName(), user.getStatus());
  }
}
