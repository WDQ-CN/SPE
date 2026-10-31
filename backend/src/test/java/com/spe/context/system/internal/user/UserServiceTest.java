package com.spe.context.system.internal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 用户维护服务的行为锁定（10 §3 W2 / §1 出口判据 2、4）。
 *
 * <p>重点三条：①状态迁移影响行数为 0 → 3xxx（06 §2.4 红线）②账号唯一校验 → 2xxx（DB 唯一键兜底）
 * ③审计与业务写同事务（本单测只能验证「调用了 AuditFacade」，同事务由事务传播与集成测试保证）。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private SysUserMapper sysUserMapper;
  @Mock private AuditFacade auditFacade;

  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  private UserService userService;

  @BeforeEach
  void setUp() {
    userService = new UserService(sysUserMapper, passwordEncoder, auditFacade);
  }

  private static SysUser user(long id, UserStatus status, int version) {
    SysUser user = new SysUser();
    user.setId(id);
    user.setUsername("zhangsan");
    user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuv");
    user.setName("张三");
    user.setRole(Role.EVALUATOR);
    user.setDepartment(Department.QUALITY);
    user.setStatus(status);
    user.setVersion(version);
    return user;
  }

  @Test
  @DisplayName("启停：守卫 UPDATE 0 行且账号**存在** → 3001，且不写审计（10 §3 W2 红线）")
  void statusChangeConflictReturnsConflict() {
    when(sysUserMapper.updateStatusAndVersion(7L, UserStatus.DISABLED, UserStatus.ENABLED, 3)).thenReturn(0);
    // P2-7：0 行之后要拿"存不存在"分类，所以这条用例必须把"存在"这一半钉住
    when(sysUserMapper.findById(7L)).thenReturn(Optional.of(user(7L, UserStatus.ENABLED, 2)));

    assertThatThrownBy(() -> userService.changeStatus(7L, UserStatus.DISABLED, 3, 1L))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    verifyNoInteractions(auditFacade);
  }

  /**
   * P2-7（03 §38）：账号根本不存在时返回 3001 是**说谎**——前端会提示"数据已被他人改动，请刷新"，
   * 用户对着一个不存在的 id 反复重试。同类 {@code resetPassword}（0 行 → 2006）本来就是另一套口径。
   */
  @Test
  @DisplayName("启停：守卫 UPDATE 0 行且账号不存在 → 2006，不是 3001")
  void statusChangeOfMissingAccountReturnsNotFound() {
    when(sysUserMapper.updateStatusAndVersion(7L, UserStatus.DISABLED, UserStatus.ENABLED, 3)).thenReturn(0);
    when(sysUserMapper.findById(7L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userService.changeStatus(7L, UserStatus.DISABLED, 3, 1L))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.USER_NOT_FOUND);
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("启停：成功时审计记 from/to，且前置状态由目标状态反推而非查库")
  void statusChangeRecordsAuditWithTransition() {
    when(sysUserMapper.updateStatusAndVersion(7L, UserStatus.ENABLED, UserStatus.DISABLED, 5)).thenReturn(1);

    userService.changeStatus(7L, UserStatus.ENABLED, 5, 1L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditFacade).record(eq(AuditAction.USER_STATUS_CHANGE), any(), eq(7L), detail.capture());
    assertThat(detail.getValue()).containsEntry("from", "DISABLED").containsEntry("to", "ENABLED");
    // 守卫条件里的前置状态来自目标状态的两态推导，过程中没有「先查状态」的读取
    verify(sysUserMapper, never()).findById(anyLong());
    // 启用方向不涉及管理员移除，不应取管理员锁（P2-8）
    verify(sysUserMapper, never()).lockEnabledAdminIds();
  }

  // ---- P2-8 管理员自锁守卫（26 §3.2 / §3.4 的 U1~U6）----

  private static SysUser adminUser(long id, UserStatus status, int version) {
    SysUser user = user(id, status, version);
    user.setUsername("admin" + id);
    user.setName("管理员" + id);
    user.setRole(Role.ADMIN);
    return user;
  }

  @Test
  @DisplayName("P2-8 U1：唯一管理员停用自己 → 3002，且不执行 UPDATE、不写审计")
  void cannotDisableSelf() {
    when(sysUserMapper.lockEnabledAdminIds()).thenReturn(List.of(7L));

    assertThatThrownBy(() -> userService.changeStatus(7L, UserStatus.DISABLED, 3, 7L))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SELF_DISABLE_FORBIDDEN);
    verify(sysUserMapper, never()).updateStatusAndVersion(anyLong(), any(), any(), anyInt());
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("P2-8 U2：停用最后一个启用中的管理员（目标不是自己）→ 3003，且不写审计")
  void cannotDisableLastEnabledAdmin() {
    when(sysUserMapper.lockEnabledAdminIds()).thenReturn(List.of(7L));

    assertThatThrownBy(() -> userService.changeStatus(7L, UserStatus.DISABLED, 3, 1L))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.LAST_ENABLED_ADMIN);
    verify(sysUserMapper, never()).updateStatusAndVersion(anyLong(), any(), any(), anyInt());
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("P2-8 U3：另有启用管理员时，停用目标管理员放行，且守卫先取锁")
  void canDisableAdminWhenAnotherAdminRemains() {
    when(sysUserMapper.lockEnabledAdminIds()).thenReturn(List.of(1L, 7L));
    when(sysUserMapper.updateStatusAndVersion(7L, UserStatus.DISABLED, UserStatus.ENABLED, 3))
        .thenReturn(1);

    userService.changeStatus(7L, UserStatus.DISABLED, 3, 1L);

    verify(sysUserMapper).lockEnabledAdminIds();
    verify(sysUserMapper).updateStatusAndVersion(7L, UserStatus.DISABLED, UserStatus.ENABLED, 3);
    verify(auditFacade).record(eq(AuditAction.USER_STATUS_CHANGE), any(), eq(7L), any());
  }

  @Test
  @DisplayName("P2-8 U4：停用非管理员不受守卫影响（锁列表不含目标即跳过判定）")
  void disablingNonAdminSkipsGuard() {
    when(sysUserMapper.lockEnabledAdminIds()).thenReturn(List.of(1L));
    when(sysUserMapper.updateStatusAndVersion(7L, UserStatus.DISABLED, UserStatus.ENABLED, 3))
        .thenReturn(1);

    userService.changeStatus(7L, UserStatus.DISABLED, 3, 1L);

    verify(sysUserMapper).updateStatusAndVersion(7L, UserStatus.DISABLED, UserStatus.ENABLED, 3);
  }

  @Test
  @DisplayName("P2-8 U5：最后一个管理员给自己降权 → 3003，且不执行档案更新、不写审计")
  void cannotDemoteLastEnabledAdmin() {
    when(sysUserMapper.findById(2L)).thenReturn(Optional.of(adminUser(2L, UserStatus.ENABLED, 1)));
    when(sysUserMapper.lockEnabledAdminIds()).thenReturn(List.of(2L));

    assertThatThrownBy(
            () -> userService.update(2L, "管理员2", Role.MANAGER, Department.QUALITY, 1))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.LAST_ENABLED_ADMIN);
    verify(sysUserMapper, never())
        .updateProfile(anyLong(), any(), any(), any(), anyInt());
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("P2-8 U6a：另有启用管理员时，给管理员降权放行")
  void canDemoteAdminWhenAnotherAdminRemains() {
    when(sysUserMapper.findById(2L)).thenReturn(Optional.of(adminUser(2L, UserStatus.ENABLED, 1)));
    when(sysUserMapper.lockEnabledAdminIds()).thenReturn(List.of(1L, 2L));
    when(sysUserMapper.updateProfile(2L, "管理员2", Role.MANAGER, Department.QUALITY, 1)).thenReturn(1);

    userService.update(2L, "管理员2", Role.MANAGER, Department.QUALITY, 1);

    verify(sysUserMapper).lockEnabledAdminIds();
    verify(auditFacade).record(eq(AuditAction.USER_UPDATE), any(), eq(2L), any());
  }

  @Test
  @DisplayName("P2-8 U6b：另有启用管理员时，管理员给自己降权放行")
  void canDemoteSelfWhenAnotherAdminRemains() {
    when(sysUserMapper.findById(1L)).thenReturn(Optional.of(adminUser(1L, UserStatus.ENABLED, 1)));
    when(sysUserMapper.lockEnabledAdminIds()).thenReturn(List.of(1L, 2L));
    when(sysUserMapper.updateProfile(1L, "管理员1", Role.VIEWER, Department.QUALITY, 1)).thenReturn(1);

    userService.update(1L, "管理员1", Role.VIEWER, Department.QUALITY, 1);

    verify(sysUserMapper).lockEnabledAdminIds();
    verify(auditFacade).record(eq(AuditAction.USER_UPDATE), any(), eq(1L), any());
  }

  @Test
  @DisplayName("P2-8 U7：改非管理员的角色也先取管理员锁，目标不在列表内则放行")
  void roleChangeOfNonAdminLocksThenSkipsGuard() {
    when(sysUserMapper.lockEnabledAdminIds()).thenReturn(List.of());
    when(sysUserMapper.findById(3L)).thenReturn(Optional.of(user(3L, UserStatus.ENABLED, 1)));
    when(sysUserMapper.updateProfile(3L, "张三", Role.MANAGER, Department.QUALITY, 1)).thenReturn(1);

    userService.update(3L, "张三", Role.MANAGER, Department.QUALITY, 1);

    // 锁先于 findById：判定只依赖锁定读的结果，不由快照决定是否加锁
    verify(sysUserMapper).lockEnabledAdminIds();
    verify(auditFacade).record(eq(AuditAction.USER_UPDATE), any(), eq(3L), any());
  }

  @Test
  @DisplayName("P2-8 U8：请求 version 与当前行不一致 → 3001，且不执行 UPDATE、不写审计")
  void updateRejectsVersionMismatchBeforeGuard() {
    // 即使目标是最后一个管理员（锁列表含它），版本不一致也必须先报 3001：
    // 否则守卫会基于「客户端声明的版本」与「快照」错位，形成可构造的绕过。
    when(sysUserMapper.lockEnabledAdminIds()).thenReturn(List.of(2L));
    when(sysUserMapper.findById(2L)).thenReturn(Optional.of(adminUser(2L, UserStatus.ENABLED, 2)));

    assertThatThrownBy(
            () -> userService.update(2L, "管理员2", Role.MANAGER, Department.QUALITY, 1))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
    verify(sysUserMapper, never()).updateProfile(anyLong(), any(), any(), any(), anyInt());
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("新增：账号已存在 → 2002，且不插库")
  void createRejectsDuplicateUsername() {
    when(sysUserMapper.countByUsername("zhangsan")).thenReturn(1L);

    assertThatThrownBy(
            () -> userService.create("zhangsan", "Passw0rd!", "张三", Role.EVALUATOR, Department.QUALITY))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.USERNAME_EXISTS);
    verify(sysUserMapper, never()).insert(any(SysUser.class));
  }

  @Test
  @DisplayName("新增：并发撞上 DB 唯一键 → 仍翻译成 2002，不漏成 5xxx（06 §4 最后防线）")
  void createTranslatesDuplicateKeyException() {
    when(sysUserMapper.countByUsername("zhangsan")).thenReturn(0L);
    when(sysUserMapper.insert(any(SysUser.class))).thenThrow(new DuplicateKeyException("uk_sys_user_username"));

    assertThatThrownBy(
            () -> userService.create("zhangsan", "Passw0rd!", "张三", Role.EVALUATOR, Department.QUALITY))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.USERNAME_EXISTS);
  }

  @Test
  @DisplayName("新增：口令入库前经 BCrypt 编码，审计不含口令")
  void createHashesPasswordAndAudits() {
    when(sysUserMapper.countByUsername("zhangsan")).thenReturn(0L);
    when(sysUserMapper.insert(any(SysUser.class)))
        .thenAnswer(invocation -> {
          invocation.getArgument(0, SysUser.class).setId(9L);
          return 1;
        });

    UserSummary created =
        userService.create("zhangsan", "Passw0rd!", "张三", Role.EVALUATOR, Department.QUALITY);

    ArgumentCaptor<SysUser> inserted = ArgumentCaptor.forClass(SysUser.class);
    verify(sysUserMapper).insert(inserted.capture());
    assertThat(inserted.getValue().getPasswordHash()).startsWith("$2a$").isNotEqualTo("Passw0rd!");
    assertThat(passwordEncoder.matches("Passw0rd!", inserted.getValue().getPasswordHash())).isTrue();
    assertThat(inserted.getValue().getStatus()).isEqualTo(UserStatus.ENABLED);
    assertThat(created.id()).isEqualTo(9L);
    assertThat(created.version()).isZero();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditFacade).record(eq(AuditAction.USER_CREATE), any(), eq(9L), detail.capture());
    // 用 containsOnlyKeys 而不是 doesNotContainKey("password")：后者在 detail **为空 map** 时会
    // 空洞地通过（"不含 password"对空 map 也成立），于是删光审计内容也照样绿。
    // 同一仓库的 SupplierServiceTest 用的是严格写法，这里统一（03 §26.5）。
    assertThat(detail.getValue())
        .containsOnlyKeys("username", "name", "role", "department");
  }

  @Test
  @DisplayName("编辑：账号不存在 → 2006；版本不符（守卫 0 行）→ 3001")
  void updateDistinguishesMissingUserAndConflict() {
    when(sysUserMapper.findById(1L)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> userService.update(1L, "张三", Role.EVALUATOR, Department.QUALITY, 0))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.USER_NOT_FOUND);

    // 快照版本与请求版本一致（0）才能走到守卫 UPDATE；0 行代表读后被并发改动 → 3001
    when(sysUserMapper.findById(2L)).thenReturn(Optional.of(user(2L, UserStatus.ENABLED, 0)));
    when(sysUserMapper.updateProfile(2L, "李四", Role.MANAGER, Department.PURCHASE, 0)).thenReturn(0);
    assertThatThrownBy(
            () -> userService.update(2L, "李四", Role.MANAGER, Department.PURCHASE, 0))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
  }

  @Test
  @DisplayName("编辑：成功时审计只记真正变化的字段的前后值")
  void updateAuditsOnlyChangedFields() {
    // 第一次读用于存在性 + 审计的 before，第二次读用于回填响应（version 已 +1）
    when(sysUserMapper.findById(2L))
        .thenReturn(
            Optional.of(user(2L, UserStatus.ENABLED, 1)), Optional.of(user(2L, UserStatus.ENABLED, 2)));
    when(sysUserMapper.updateProfile(2L, "张三", Role.MANAGER, Department.QUALITY, 1)).thenReturn(1);

    userService.update(2L, "张三", Role.MANAGER, Department.QUALITY, 1);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
    verify(auditFacade).record(eq(AuditAction.USER_UPDATE), any(), eq(2L), detail.capture());
    assertThat(detail.getValue()).containsOnlyKeys("role");
  }

  @Test
  @DisplayName("重置口令：过短 → 2003；账号不存在（0 行）→ 2006")
  void resetPasswordValidatesAndMapsMissingUser() {
    assertThatThrownBy(() -> userService.resetPassword(1L, "short"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.PASSWORD_TOO_WEAK);

    when(sysUserMapper.resetPassword(eq(1L), any())).thenReturn(0);
    assertThatThrownBy(() -> userService.resetPassword(1L, "Passw0rd!"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.USER_NOT_FOUND);
  }

  @Test
  @DisplayName("分页：page 下界收敛为 1，size 上界收敛为 100，offset 按「page 从 1 开始」换算")
  void pageClampsParameters() {
    when(sysUserMapper.findPage(anyLong(), anyInt())).thenReturn(List.of());
    when(sysUserMapper.countAll()).thenReturn(0L);

    PageResult<UserSummary> result = userService.page(0, 9999);

    assertThat(result.page()).isEqualTo(1);
    assertThat(result.size()).isEqualTo(PageQuery.MAX_SIZE);
    verify(sysUserMapper).findPage(0L, PageQuery.MAX_SIZE);

    userService.page(3, 10);
    verify(sysUserMapper).findPage(20L, 10);
  }
}
