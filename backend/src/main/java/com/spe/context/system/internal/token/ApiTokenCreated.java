package com.spe.context.system.internal.token;

import java.time.LocalDateTime;

/** 生成 Token 的响应：`token` 是**唯一一次**出现的明文，列表/日志/审计不得再出现它。 */
public record ApiTokenCreated(
    Long id,
    String name,
    String tokenPrefix,
    String token,
    LocalDateTime createdAt) {}
