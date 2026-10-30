package com.spe.context.system.internal.audit;

import java.time.LocalDateTime;

/** 操作日志的对外投影（SM5；detail 保持原始 JSON 字符串，避免后端拼装展示结构）。 */
public record AuditLogView(
    long id,
    Long operatorId,
    String operatorName,
    String action,
    String targetType,
    Long targetId,
    String detail,
    String ip,
    LocalDateTime createdAt) {

  static AuditLogView of(AuditLogRow row) {
    return new AuditLogView(
        row.getId(),
        row.getOperatorId(),
        row.getOperatorName(),
        row.getAction(),
        row.getTargetType(),
        row.getTargetId(),
        row.getDetail(),
        row.getIp(),
        row.getCreatedAt());
  }
}
