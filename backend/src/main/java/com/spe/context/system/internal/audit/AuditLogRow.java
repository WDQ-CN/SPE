package com.spe.context.system.internal.audit;

import java.time.LocalDateTime;
import lombok.Data;

/** 操作日志列表行（SM5；M5 W5）：detail 原样 JSON 字符串，前端格式化展示。 */
@Data
public class AuditLogRow {

  private Long id;

  private Long operatorId;

  private String operatorName;

  private String action;

  private String targetType;

  private Long targetId;

  private String detail;

  private String ip;

  private LocalDateTime createdAt;
}
