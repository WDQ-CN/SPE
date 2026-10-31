package com.spe.context.system.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 用户只读门面（05 §4.3 / §5.2）：跨上下文读取用户信息的唯一入口，实体与 Mapper 不外泄。
 *
 * <p>P3 发布任务时用它校验审批链内人员是否在职（01 §7 补充边界）；写操作不经此门面，
 * 用户维护只由 system 自己的 Controller 触发。
 */
public interface UserQueryFacade {

  Optional<UserBrief> findById(long userId);

  /** 批量取用户；入参为空返回空列表。供审批链这类「一次校验多人」的场景使用。 */
  List<UserBrief> findByIds(Collection<Long> userIds);

  /**
   * 按部门 + 角色查**在职**用户 id，**按 id 升序**（13 §E12 的自动指派）。
   *
   * <p>升序不是随手写的：多人同部门时发布任务要"取 id 最小者"，需要**确定性**——否则同一个任务重跑
   * 可能指派给不同的人，集成用例与演示都不可复现。排序在 SQL 里定死，调用方取第一个即可。
   *
   * @param department 部门 code（`sys_user.department`，见 01 §7）；为空时返回空列表（不猜部门）
   * @param roles 允许的角色集合；为空时返回空列表
   */
  List<Long> findEnabledIdsByDepartmentAndRoles(String department, Collection<Role> roles);

  /**
   * 按角色查**在职**用户 id，**按 id 升序**——不限部门。
   *
   * <p>用途：`TaskSupplierFinalizedEvent` 的「通知采购经理」（05 §5.3）。采购经理在 01 §7 里
   * 就是 `MANAGER` 角色本身，不按部门区分；事件载荷里没有"任务创建人"，所以不能按某个人定位。
   * 角色集合为空时返回空列表（不猜）。
   */
  List<Long> findEnabledIdsByRoles(Collection<Role> roles);
}
