package com.spe.context.system.api;

/**
 * 用户的跨上下文只读投影（05 §8.3：门面返回 DTO，实体永不出上下文）。
 *
 * <p>P3 发布任务时用它校验审批链内人员是否在职（01 §7 补充边界）。
 */
public record UserBrief(
    Long id, String username, String name, Role role, Department department, UserStatus status) {

  public boolean enabled() {
    return status == UserStatus.ENABLED;
  }
}
