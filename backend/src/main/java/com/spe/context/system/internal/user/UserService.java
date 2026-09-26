package com.spe.context.system.internal.user;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditDetails;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserStatus;
import com.spe.context.system.internal.security.PasswordPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户维护服务（01 SM1/A4 / 10 §3 W2）：列表分页、新增、编辑、启停、重置口令。
 *
 * <p><b>事务形态</b>（05 §5.5 T6）：每个写方法都是单一 `@Transactional`，业务行变更与
 * `AuditFacade` 留痕在同一事务内——审计写入失败则业务一并回滚；Controller 不做任何拼装式多表写。
 *
 * <p><b>状态迁移</b>：启停走带前置状态 + 版本的守卫 UPDATE，影响行数为 0 → 3xxx（06 §2.4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

  private final SysUserMapper sysUserMapper;
  private final PasswordEncoder passwordEncoder;
  private final AuditFacade auditFacade;

  /** 列表：入参规范化（`page` 下界、`size` 上限）交给 {@link PageQuery}，这里只取数与组装。 */
  @Transactional(readOnly = true)
  public PageResult<UserSummary> page(int page, int size) {
    PageQuery query = PageQuery.of(page, size);
    var rows =
        sysUserMapper.findPage(query.offset(), query.size()).stream().map(UserSummary::of).toList();
    return PageResult.of(rows, sysUserMapper.countAll(), query.page(), query.size());
  }

  /** 新增用户：账号唯一校验走 2xxx，DB 唯一键是最后防线（06 §4）。 */
  @Transactional
  public UserSummary create(
      String username, String rawPassword, String name, Role role, Department department) {
    PasswordPolicy.validateOrThrow(rawPassword);
    if (sysUserMapper.countByUsername(username) > 0) {
      throw new BizException(ErrorCode.USERNAME_EXISTS);
    }

    SysUser user = new SysUser();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(rawPassword));
    user.setName(name);
    user.setRole(role);
    user.setDepartment(department);
    user.setStatus(UserStatus.ENABLED);
    user.setVersion(0);
    try {
      sysUserMapper.insert(user);
    } catch (DuplicateKeyException ex) {
      // 并发下「先查后插」仍可能撞上唯一键（uk_sys_user_username）：DB 才是最终防线，
      // 这里把约束冲突翻译成与前置校验一致的 2xxx，而不是漏成 5xxx。
      throw new BizException(ErrorCode.USERNAME_EXISTS);
    }

    auditFacade.record(AuditAction.USER_CREATE, AuditTargetType.USER, user.getId(), createDetail(user));
    log.info("创建用户 {}（id={}）", username, user.getId());
    return UserSummary.of(user);
  }

  /**
   * 编辑档案（姓名/角色/部门）。
   *
   * <p>先读一次是为了①账号是否存在（给 2006 而不是笼统冲突）②审计里的「改动字段与前后值」；
   * **迁移本身仍是带 `version` 前置条件的 UPDATE**——不是「先 SELECT 判断状态再 UPDATE」那种
   * 竞态写法（06 §2.4 红线针对的是状态迁移的判断依据，本方法没有任何状态判断）。
   */
  @Transactional
  public UserSummary update(
      long id, String name, Role role, Department department, int version) {
    SysUser current = sysUserMapper.findById(id).orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
    if (sysUserMapper.updateProfile(id, name, role, department, version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    AuditDetails.putIfChanged(detail, "name", current.getName(), name);
    AuditDetails.putIfChanged(detail, "role", current.getRole(), role);
    AuditDetails.putIfChanged(detail, "department", current.getDepartment(), department);
    auditFacade.record(AuditAction.USER_UPDATE, AuditTargetType.USER, id, detail);

    log.info("编辑用户 id={}，改动 {}", id, detail.keySet());
    // 不再回查一次：返回的字段要么来自入参（name/role/department），要么来自上面那次读（username/status），
    // version 由 updateProfile 的 SQL 决定——它是 `SET ..., version = version + 1`，与 Mapper 同源；
    // 「编辑后 version 自增」另有集成测试断言，SQL 若改动会先在那里变红。
    // 编辑是 P1 里唯一「写后再读」的路径，去掉它省一次主键查询（压测基线见 03 §18）。
    return new UserSummary(
        current.getId(), current.getUsername(), name, role, department, current.getStatus(), version + 1);
  }

  /**
   * 启停账号（01 A4/SM1）：目标状态的两态机 → 前置状态由 {@link UserStatus#other()} 推出，
   * **不查当前状态**，一条守卫 UPDATE 完成判断与迁移；0 行（版本已变或已处于目标状态）→ 3xxx。
   */
  @Transactional
  public void changeStatus(long id, UserStatus target, int version) {
    UserStatus expected = target.other();
    if (sysUserMapper.updateStatusAndVersion(id, target, expected, version) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    auditFacade.record(
        AuditAction.USER_STATUS_CHANGE,
        AuditTargetType.USER,
        id,
        Map.of("from", expected.name(), "to", target.name()));
    log.info("用户状态迁移 id={}：{} → {}", id, expected, target);
  }

  /** ADMIN 重置他人口令（01 SM1 / E3）：非状态迁移，只以主键为条件；0 行 → 账号不存在。 */
  @Transactional
  public void resetPassword(long id, String newRawPassword) {
    PasswordPolicy.validateOrThrow(newRawPassword);
    if (sysUserMapper.resetPassword(id, passwordEncoder.encode(newRawPassword)) == 0) {
      throw new BizException(ErrorCode.USER_NOT_FOUND);
    }
    // detail 不含口令本身（06 §2.8）；target_id 已能定位被重置的账号
    auditFacade.record(AuditAction.USER_PASSWORD_RESET, AuditTargetType.USER, id, null);
    log.info("重置用户口令 id={}", id);
  }

  private static Map<String, Object> createDetail(SysUser user) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("username", user.getUsername());
    detail.put("name", user.getName());
    detail.put("role", user.getRole().name());
    detail.put("department", user.getDepartment().name());
    return detail;
  }
}
