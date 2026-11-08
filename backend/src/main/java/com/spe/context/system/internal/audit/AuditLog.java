package com.spe.context.system.internal.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 操作日志实体（`V4__audit_log.sql` / 01 SM5）。只追加，不更新、不设 version。
 *
 * <p>`operatorId`/`operatorName`/`targetType`/`targetId`/`detail` 均可空：登录失败时操作人未知、
 * 部分动作没有明确目标（见各列注释）。
 */
@Data
@TableName("audit_log")
public class AuditLog {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long operatorId;

  private String operatorName;

  /** 动作码（`<对象>_<动作>`，06 §2.8）。 */
  private String action;

  private String targetType;

  private Long targetId;

  /** 业务细节的 JSON 原文；**不含口令与令牌**（06 §2.8）。 */
  private String detail;

  private String ip;

  private String traceId;
}
