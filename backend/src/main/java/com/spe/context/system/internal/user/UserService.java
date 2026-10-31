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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

  /** 选择器的单次上限（配合 `SysUserMapper.findEnabledOptions` 那条**登记例外**：不分页，但必须硬截断）。 */
  private static final int OPTION_LIMIT = 200;

  /**
   * 可选人员（02 §4 / 03 §38 的 P0-1）：在职、按 id 升序、只出目录面字段。
   *
   * <p>角色白名单在**这里**校验，不靠"前端只会传对的值"：`roles` 决定的是"谁能看到谁"，
   * 不能由 query 任意扩大。非法取值 → 2001 + 固定文案（不外泄枚举/内部形状，P2-14 同一条口径）。
   */
  @Transactional(readOnly = true)
  public List<UserOption> options(Collection<String> roleNames) {
    List<String> roles = new ArrayList<>();
    if (roleNames != null) {
      for (String name : roleNames) {
        if (name == null || name.isBlank()) {
          continue;
        }
        try {
          roles.add(Role.valueOf(name.trim().toUpperCase(Locale.ROOT)).name());
        } catch (IllegalArgumentException notARole) {
          throw new BizException(ErrorCode.BAD_REQUEST, "角色取值不在允许范围内");
        }
      }
    }
    List<SysUser> rows = sysUserMapper.findEnabledOptions(roles, OPTION_LIMIT);
    if (rows.size() >= OPTION_LIMIT) {
      log.warn("可选人员达到单次上限 {}，超出部分未返回（需要全量请用分页的用户管理接口）", OPTION_LIMIT);
    }
    return rows.stream().map(UserOption::of).toList();
  }

  /** 新增用户：账号唯一校验走 2xxx，DB 唯一键是最后防线（06 §4）。 */  @Transactional
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
   * <p><b>P2-8（03 §50 P1-1）</b>：降权方向在**任何普通读之前**先锁管理员集（06 §2.4 的「守卫前哨」），
   * 判定只依赖锁定读的结果——不由 `findById` 的快照决定是否加锁，否则客户端提供一个超前的
   * `version` 就可能跳过守卫。随后读一次用于①账号是否存在（2006）②审计的前后值
   * ③版本一致性：请求 `version` 必须等于当前行版本，否则 3001（避免快照与写入版本错位）。
   * **迁移本身仍是带 `version` 前置条件的 UPDATE**——不是「先 SELECT 判断状态再 UPDATE」那种
   * 竞态写法（06 §2.4 红线针对的是状态迁移的判断依据；本方法的写仍带 version 条件）。
   */
  @Transactional
  public UserSummary update(
      long id, String name, Role role, Department department, int version) {
    // P2-8：锁是事务第一条语句；role == ADMIN（升级/不变）不涉及管理员移除，无需取锁。
    List<Long> enabledAdmins =
        role == Role.ADMIN ? List.of() : sysUserMapper.lockEnabledAdminIds();
    SysUser current = sysUserMapper.findById(id).orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
    if (current.getVersion() != version) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }
    if (enabledAdmins.contains(id) && enabledAdmins.size() == 1) {
      throw new BizException(ErrorCode.LAST_ENABLED_ADMIN);
    }
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
   * **不查目标当前状态**，一条守卫 UPDATE 完成判断与迁移。
   *
   * <p><b>P2-8 管理员自锁守卫</b>（`26` §3.2）：停用方向先做一次以「目标是否为启用中的 ADMIN」
   * 为目的的锁定读——是则：目标是操作者自己 ⇒ 3002；系统只剩这一个 ⇒ 3003。锁定读是事务第一条语句，
   * 让并发的管理员移除串行化；它与随后的守卫 UPDATE 的关系见 06 §2.4 的「守卫前哨」条款。
   *
   * <p>⚠️ 0 行有**两种**含义，必须分开（03 §38 的 P2-7）：账号不存在 ⇒ 2006，存在但版本/状态对不上 ⇒ 3001。
   * 分类读发生在守卫 UPDATE **之后**、只负责把"0 行"拆开，不是 `06 §2.4` 禁的"先 SELECT 判断再 UPDATE"
   * （那个的病是"判断基于快照、写不带条件"），与 `TaskService.updateDraft` 同形。
   * 两条都给 3001 时，与同类 {@link #resetPassword}（0 行 → 2006）自相矛盾，前端会引导用户反复刷新重试。
   */
  @Transactional
  public void changeStatus(long id, UserStatus target, int version, long operatorId) {
    // P2-8：停用方向先过管理员自锁守卫（26 §3.2）。锁定读是事务第一条语句，把并发的
    // "停用管理员"串行化；它只用于判定，随后的守卫 UPDATE 仍带 status + version 条件。
    if (target == UserStatus.DISABLED) {
      List<Long> enabledAdmins = sysUserMapper.lockEnabledAdminIds();
      if (enabledAdmins.contains(id)) {
        if (id == operatorId) {
          throw new BizException(ErrorCode.SELF_DISABLE_FORBIDDEN);
        }
        if (enabledAdmins.size() == 1) {
          throw new BizException(ErrorCode.LAST_ENABLED_ADMIN);
        }
      }
    }
    UserStatus expected = target.other();
    if (sysUserMapper.updateStatusAndVersion(id, target, expected, version) == 0) {
      throw sysUserMapper.findById(id).isEmpty()
          ? new BizException(ErrorCode.USER_NOT_FOUND)
          : new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    auditFacade.record(
        AuditAction.USER_STATUS_CHANGE,
        AuditTargetType.USER,
        id,
        AuditDetails.transition(expected, target));
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
