package com.spe.context.system.internal.audit;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** `audit_log` 的写 Mapper（只追加）。 */
@Mapper
public interface AuditLogMapper {

  /**
   * 追加一条日志。`created_at` 交给列默认值 `CURRENT_TIMESTAMP(3)`——
   * 由库生成时间戳可避免应用与库的时钟口径不一致（09 D3 的 UTC 约定）。
   */
  @Insert(
      """
      INSERT INTO audit_log
        (operator_id, operator_name, action, target_type, target_id, detail, ip, trace_id)
      VALUES
        (#{operatorId}, #{operatorName}, #{action}, #{targetType}, #{targetId}, #{detail}, #{ip},
         #{traceId})
      """)
  int insert(AuditLog log);
}
