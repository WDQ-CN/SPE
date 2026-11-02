package com.spe.context.system.api;

import java.time.LocalDateTime;

/**
 * 审计摘要条目（只读门面返回）：用于业务详情页展示某个目标的动作轨迹。
 *
 * <p>`detail` 保持原始 JSON 字符串，由前端决定如何展开；不在这里拼装展示结构。
 */
public record AuditTrailEntry(
    long id,
    String action,
    Long operatorId,
    String operatorName,
    String detail,
    LocalDateTime createdAt) {}
