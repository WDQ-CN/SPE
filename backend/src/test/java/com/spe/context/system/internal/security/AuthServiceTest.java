package com.spe.context.system.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserStatus;
import com.spe.context.system.internal.user.SysUser;
import com.spe.context.system.internal.user.SysUserMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 登录与改口令的行为锁定（10 §1 出口判据 2、4 与 §E2/E3/E12）。
 *
 * <p>JwtService 用真实实现（纯函数、无外部依赖），因此「签发的令牌能被解析回 uid/角色/部门」
 * 是被真正验证的，而不是用 mock 假装通过。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  private static final String SECRET = "spe-p1-test-secret-0123456789abcdef";
  private static final String RAW_PASSWORD = "Admin@12345";

  @Mock private SysUserMapper sysUserMapper;
  @Mock private AuditFacade auditFacade;

  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private final JwtService jwtService = new JwtService(new JwtProperties(SECRET, Duration.ofHours(2)));

  private AuthService authService;
  private LoginAttemptLimiter loginAttemptLimiter;

  @BeforeEach
  void setUp() {
    // 每个用例一个独立限流器（固定时钟）：避免用例之间通过失败计数互相影响
    loginAttemptLimiter =
        new LoginAttemptLimiter(
            Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC),
            LoginLimiterProperties.defaults(),
            new SimpleMeterRegistry());
    authService =
        new AuthService(sysUserMapper, passwordEncoder, jwtService, auditFacade, loginAttemptLimiter);
  }

  @Test
  @DisplayName("连续失败达到阈值 → 后续登录直接 1003，且只为「进入锁定」那一刻留一条 AUTH_LOGIN_BLOCKED")
  void loginIsBlockedAfterTooManyFailures() {
    when(sysUserMapper.findByUsernameForAuth("zhangsan")).thenReturn(Optional.empty());

    for (int i = 0; i < LoginLimiterProperties.DEFAULT_MAX_FAILURES_PER_ACCOUNT; i++) {
      assertThatThrownBy(() -> authService.login("zhangsan", "wrong-password"))
          .isInstanceOf(BizException.class)
          .extracting(ex -> ((BizException) ex).errorCode())
          .isEqualTo(ErrorCode.LOGIN_FAILED);
    }

    // 只为「进入锁定」那一刻写一条 BLOCKED 审计（锁定期内的后续拒绝不再写库，避免撑大 audit_log）
    verify(auditFacade)
        .recordForOperator(
            isNull(), eq("zhangsan"), eq(AuditAction.AUTH_LOGIN_BLOCKED), isNull(), isNull(), isNull());

    assertThatThrownBy(() -> authService.login("zhangsan", "wrong-password"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.LOGIN_TOO_MANY_ATTEMPTS);
  }

  private SysUser user(UserStatus status) {
    SysUser user = new SysUser();
    user.setId(1L);
    user.setUsername("admin");
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setName("系统管理员");
    user.setRole(Role.ADMIN);
    user.setDepartment(Department.OTHER);
    user.setStatus(status);
    user.setVersion(0);
    return user;
  }

  @Test
  @DisplayName("登录成功：签发可解析的令牌（uid/账号/角色/部门），并按已校验账号记成功审计")
  void loginIssuesTokenAndAudits() {
    when(sysUserMapper.findByUsernameForAuth("admin")).thenReturn(Optional.of(user(UserStatus.ENABLED)));

    AuthService.LoginResult result = authService.login("admin", RAW_PASSWORD);

    assertThat(result.name()).isEqualTo("系统管理员");
    assertThat(result.roles()).containsExactly("ADMIN");
    assertThat(result.department()).isEqualTo("OTHER");

    JwtPayload payload = jwtService.parse(result.token());
    assertThat(payload.uid()).isEqualTo(1L);
    assertThat(payload.username()).isEqualTo("admin");
    assertThat(payload.roles()).containsExactly("ADMIN");

    verify(auditFacade)
        .recordForOperator(eq(1L), eq("admin"), eq(AuditAction.AUTH_LOGIN_SUCCESS), isNull(), isNull(), any());
  }

  @Test
  @DisplayName("口令错误：1002 + 失败审计（操作人 id 为空、记被尝试的账号）")
  void wrongPasswordIsRejectedWithLoginFailed() {
    when(sysUserMapper.findByUsernameForAuth("admin")).thenReturn(Optional.of(user(UserStatus.ENABLED)));

    assertThatThrownBy(() -> authService.login("admin", "wrong-password"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.LOGIN_FAILED);
    verify(auditFacade)
        .recordForOperator(isNull(), eq("admin"), eq(AuditAction.AUTH_LOGIN_FAILED), isNull(), isNull(), isNull());
  }

  @Test
  @DisplayName("账号不存在：与口令错误返回同一个码（不给账号枚举信号，10 §E12）")
  void unknownAccountIsIndistinguishable() {
    when(sysUserMapper.findByUsernameForAuth("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authService.login("ghost", RAW_PASSWORD))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.LOGIN_FAILED);
    // 口令错误与账号不存在连文案都一致，避免通过响应区分两种失败
    assertThat(ErrorCode.LOGIN_FAILED.message()).isEqualTo("账号或密码错误");
  }

  @Test
  @DisplayName("账号已停用：口令正确也拒绝，且不签发令牌（01 A4 出口判据 2）")
  void disabledAccountCannotLogin() {
    when(sysUserMapper.findByUsernameForAuth("admin")).thenReturn(Optional.of(user(UserStatus.DISABLED)));

    assertThatThrownBy(() -> authService.login("admin", RAW_PASSWORD))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.LOGIN_FAILED);
    verify(auditFacade)
        .recordForOperator(isNull(), eq("admin"), eq(AuditAction.AUTH_LOGIN_FAILED), isNull(), isNull(), isNull());
  }

  @Test
  @DisplayName("改口令：新口令过短 → 2003，原口令不校验、不写库")
  void changePasswordRejectsWeakNewPassword() {
    assertThatThrownBy(() -> authService.changeOwnPassword("admin", RAW_PASSWORD, "short"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.PASSWORD_TOO_WEAK);
    verify(sysUserMapper, never()).changePassword(org.mockito.ArgumentMatchers.anyLong(), any(), any());
  }

  @Test
  @DisplayName("改口令：原口令不符 → 2004，不改库")
  void changePasswordRejectsWrongOldPassword() {
    when(sysUserMapper.findByUsernameForAuth("admin")).thenReturn(Optional.of(user(UserStatus.ENABLED)));

    assertThatThrownBy(() -> authService.changeOwnPassword("admin", "not-the-password", "NewPassw0rd!"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.PASSWORD_MISMATCH);
    verifyNoInteractions(auditFacade);
  }

  @Test
  @DisplayName("改口令：以原哈希为前置条件的 UPDATE 影响 0 行（并发插队）→ 3001")
  void changePasswordConflictWhenGuardMisses() {
    SysUser current = user(UserStatus.ENABLED);
    when(sysUserMapper.findByUsernameForAuth("admin")).thenReturn(Optional.of(current));
    when(sysUserMapper.changePassword(eq(1L), any(), eq(current.getPasswordHash()))).thenReturn(0);

    assertThatThrownBy(() -> authService.changeOwnPassword("admin", RAW_PASSWORD, "NewPassw0rd!"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.CONCURRENT_CONFLICT);
  }

  @Test
  @DisplayName("改口令成功：新口令经 BCrypt 编码后写库（旧口令失效）、审计留痕")
  void changePasswordSucceeds() {
    SysUser current = user(UserStatus.ENABLED);
    when(sysUserMapper.findByUsernameForAuth("admin")).thenReturn(Optional.of(current));
    when(sysUserMapper.changePassword(eq(1L), any(), eq(current.getPasswordHash()))).thenReturn(1);

    authService.changeOwnPassword("admin", RAW_PASSWORD, "NewPassw0rd!");

    // 抓住真正写进库的那串哈希：只 verify 审计的话，实现改成存明文（或存错口令的哈希）也照样绿
    ArgumentCaptor<String> writtenHash = ArgumentCaptor.forClass(String.class);
    verify(sysUserMapper).changePassword(eq(1L), writtenHash.capture(), eq(current.getPasswordHash()));
    assertThat(passwordEncoder.matches("NewPassw0rd!", writtenHash.getValue()))
        .as("写库的哈希必须能校验新口令")
        .isTrue();
    assertThat(passwordEncoder.matches(RAW_PASSWORD, writtenHash.getValue()))
        .as("旧口令不应再能通过")
        .isFalse();

    verify(auditFacade).record(eq(AuditAction.USER_PASSWORD_CHANGE), isNull(), isNull(), isNull());
  }
}
