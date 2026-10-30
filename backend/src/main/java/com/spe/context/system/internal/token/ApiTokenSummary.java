package com.spe.context.system.internal.token;

import com.spe.context.system.api.ApiTokenStatus;
import java.time.LocalDateTime;

/** Token 列表行：**永不包含** token/token_hash；version 供停用乐观锁。 */
public record ApiTokenSummary(
    Long id,
    String name,
    String tokenPrefix,
    ApiTokenStatus status,
    LocalDateTime lastUsedAt,
    LocalDateTime createdAt,
    Integer version) {

  static ApiTokenSummary of(ApiToken token) {
    return new ApiTokenSummary(
        token.getId(),
        token.getName(),
        token.getTokenPrefix(),
        token.getStatus(),
        token.getLastUsedAt(),
        token.getCreatedAt(),
        token.getVersion());
  }
}
