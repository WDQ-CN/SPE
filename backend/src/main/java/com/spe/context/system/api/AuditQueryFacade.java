package com.spe.context.system.api;

import java.util.List;

/**
 * 审计只读门面（W4 详情审计摘要）：按目标类型与目标 id 取动作轨迹。
 *
 * <p>与 {@link AuditFacade} 分开：写入口继续只由业务事务调用；读入口只给详情页做摘要，
 * 不暴露 `audit_log` 的完整查询能力（操作日志页仍走 system 自己的 Controller）。
 */
public interface AuditQueryFacade {

  /**
   * 按时间正序返回目标轨迹。
   *
   * @param limit 最大条数，实现会收敛到 `[1,100]`，避免详情页被超长历史拖垮
   */
  List<AuditTrailEntry> listForTarget(AuditTargetType targetType, long targetId, int limit);
}
