package com.spe.context.system.api;

import java.util.Map;

/**
 * 审计门面（05 §4.3 / 06 §2.8）：关键动作留痕的**唯一入口**，跨上下文经本接口直调。
 *
 * <p>实现必须与调用方的业务写在**同一事务**内（05 §5.5 T6）：审计落库失败则业务整体回滚。
 * `detail` 只放可复述的业务字段，**禁止写入口令、令牌与完整请求体**。
 */
public interface AuditFacade {

  /**
   * 记录当前登录用户的操作；操作人（id 与账号）由实现从安全上下文解析，调用方不必传。
   *
   * @param action 动作码（06 §2.8）
   * @param targetType 目标类型；无明确目标时传 null
   * @param targetId 目标主键；无明确目标或目标尚无主键时传 null
   * @param detail 业务细节（可为 null）；键值须可 JSON 序列化
   */
  void record(AuditAction action, AuditTargetType targetType, Long targetId, Map<String, Object> detail);

  /**
   * 显式指定操作人：用于**登录还没建立（或正在建立）认证**的路径，此时安全上下文里没有操作人。
   *
   * <p>两个场景：登录失败（`operatorId` 为 null——账号可能根本不存在，账号名取自被尝试的输入）、
   * 登录成功（刚签发令牌，安全上下文仍是匿名，须用已校验的账号显式落痕）。
   *
   * <p>之所以单独开一个方法而不复用 {@link #record}：登录失败若去读安全上下文，会把上一个请求
   * 残留的操作人误记到本次失败尝试上（`audit_log.operator_name` 可空正是为此保留，见
   * `V4__audit_log.sql`）。
   */
  void recordForOperator(
      Long operatorId,
      String operatorName,
      AuditAction action,
      AuditTargetType targetType,
      Long targetId,
      Map<String, Object> detail);
}
