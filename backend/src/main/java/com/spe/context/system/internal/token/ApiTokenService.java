package com.spe.context.system.internal.token;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.security.ApiTokenPrincipal;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.ApiTokenStatus;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开放 API Token 管理（M6 / 01 I3）：生成、分页、停用与鉴权。
 *
 * <p>安全口径：明文只在 {@link #create} 的返回值出现一次；库里只有 SHA-256 哈希；审计只记名称/prefix。
 * 鉴权成功后 touch `last_used_at`，若期间被停用则本次认证作废。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiTokenService {

  private final ApiTokenMapper apiTokenMapper;
  private final AuditFacade auditFacade;

  @Transactional(readOnly = true)
  public PageResult<ApiTokenSummary> page(PageQuery query) {
    List<ApiTokenSummary> rows =
        apiTokenMapper.findPage(query.offset(), query.size()).stream()
            .map(ApiTokenSummary::of)
            .toList();
    return PageResult.of(rows, apiTokenMapper.countAll(), query.page(), query.size());
  }

  /** 生成：返回唯一一次明文；业务行与审计同一事务（T6）。 */
  @Transactional
  public ApiTokenCreated create(String name, long operatorId) {
    String normalized = name == null ? "" : name.trim();
    if (normalized.isEmpty()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "Token 名称不能为空");
    }
    String raw = TokenHasher.generateToken();
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    ApiToken token = new ApiToken();
    token.setName(normalized);
    token.setTokenHash(TokenHasher.hash(raw));
    token.setTokenPrefix(TokenHasher.displayPrefix(raw));
    token.setStatus(ApiTokenStatus.ENABLED);
    token.setCreatedBy(operatorId);
    token.setCreatedAt(now);
    token.setUpdatedAt(now);
    token.setVersion(0);
    apiTokenMapper.insert(token);

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("name", normalized);
    detail.put("prefix", token.getTokenPrefix());
    auditFacade.record(AuditAction.TOKEN_CREATE, AuditTargetType.TOKEN, token.getId(), detail);

    log.info("生成开放 API Token：id={} name={} prefix={}", token.getId(), normalized, token.getTokenPrefix());
    return new ApiTokenCreated(
        token.getId(), normalized, token.getTokenPrefix(), raw, token.getCreatedAt());
  }

  /** 停用（不可逆）：守卫 UPDATE，0 行按「已停用」或「版本不符」区分。 */
  @Transactional
  public void disable(long id, int version) {
    ApiToken existing =
        apiTokenMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.API_TOKEN_NOT_FOUND));
    if (existing.getStatus() == ApiTokenStatus.DISABLED) {
      throw new BizException(ErrorCode.API_TOKEN_STATUS_INVALID, "该 API Token 已停用");
    }
    if (existing.getVersion() == null || existing.getVersion() != version) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    if (apiTokenMapper.disable(id, version) == 0) {
      // 读与守卫之间被并发改动；不猜具体原因，按冲突返回
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("name", existing.getName());
    detail.put("prefix", existing.getTokenPrefix());
    detail.put("status", Map.of("before", "ENABLED", "after", "DISABLED"));
    auditFacade.record(AuditAction.TOKEN_DISABLE, AuditTargetType.TOKEN, id, detail);
    log.info("停用开放 API Token：id={} name={}", id, existing.getName());
  }

  /**
   * 鉴权：哈希查找 → 状态检查 → touch。返回空表示 401，调用方不区分「不存在/已停用」，
   * 避免给攻击者提供 Token 存在性信息。
   */
  @Transactional
  public Optional<ApiTokenPrincipal> authenticate(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      return Optional.empty();
    }
    ApiToken token = apiTokenMapper.findByHash(TokenHasher.hash(rawToken)).orElse(null);
    if (token == null || token.getStatus() != ApiTokenStatus.ENABLED) {
      return Optional.empty();
    }
    if (apiTokenMapper.touchLastUsed(token.getId()) == 0) {
      // 查询与 touch 之间被停用：本次不放行
      return Optional.empty();
    }
    log.debug("开放 API Token 鉴权成功：id={} name={}", token.getId(), token.getName());
    return Optional.of(new ApiTokenPrincipal(token.getId(), token.getName()));
  }
}
