package com.spe.context.procurement.api;

/**
 * 审批矩阵要求的角色。
 *
 * <p>满足关系：ADMIN 满足 ADMIN 与 MANAGER；MANAGER 只满足 MANAGER——否则「超过阈值需升级」形同虚设。
 */
public enum ApprovalRequiredRole {
  MANAGER,
  ADMIN;

  public boolean satisfiedBy(boolean admin) {
    return this == MANAGER || admin;
  }
}
