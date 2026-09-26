package com.spe.context.system.api;

/** 审计目标类型（锚定 `V4__audit_log.sql` 的 `target_type` 注释：USER/SUPPLIER/SETTING）。 */
public enum AuditTargetType {
  USER,
  SUPPLIER,
  SETTING
}
