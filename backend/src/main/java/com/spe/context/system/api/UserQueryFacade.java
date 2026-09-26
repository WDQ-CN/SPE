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

  /** 是否在职（`ENABLED`）；账号不存在按不在职处理。 */
  boolean isEnabled(long userId);
}
