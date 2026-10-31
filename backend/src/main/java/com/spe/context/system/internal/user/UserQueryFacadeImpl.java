package com.spe.context.system.internal.user;

import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserBrief;
import com.spe.context.system.api.UserQueryFacade;
import com.spe.context.system.api.UserStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UserQueryFacade} 的实现（05 §8.3）：只读跨上下文查询，实体不出上下文，返回 {@link UserBrief}。
 */
@Component
@RequiredArgsConstructor
public class UserQueryFacadeImpl implements UserQueryFacade {

  private final SysUserMapper sysUserMapper;

  @Override
  @Transactional(readOnly = true)
  public Optional<UserBrief> findById(long userId) {
    return sysUserMapper.findById(userId).map(UserQueryFacadeImpl::toBrief);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UserBrief> findByIds(Collection<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return List.of();
    }
    return sysUserMapper.findByIds(userIds).stream().map(UserQueryFacadeImpl::toBrief).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> findEnabledIdsByDepartmentAndRoles(String department, Collection<Role> roles) {
    if (department == null || department.isBlank() || roles == null || roles.isEmpty()) {
      return List.of();
    }
    return sysUserMapper.findEnabledIdsByDepartmentAndRoles(department, roles);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> findEnabledIdsByRoles(Collection<Role> roles) {
    if (roles == null || roles.isEmpty()) {
      return List.of();
    }
    return sysUserMapper.findEnabledIdsByRoles(roles);
  }

  private static UserBrief toBrief(SysUser user) {
    return new UserBrief(
        user.getId(),
        user.getUsername(),
        user.getName(),
        user.getRole(),
        user.getDepartment(),
        user.getStatus());
  }
}
