package com.spe.context.system.internal.security;

import com.spe.common.web.BizException;
import com.spe.common.web.ClientIpResolver;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.UserStatus;
import com.spe.context.system.internal.user.SysUser;
import com.spe.context.system.internal.user.SysUserMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录与本人改口令（01 A1/A3、09 D6、10 §E2/E3）。
 *
 * <p><b>登录失败一律 1002</b>（10 §E12）：账号不存在、口令错误、账号已停用三种情况返回同一个码与文案，
 * 不给攻击者「账号是否存在/是否停用」的枚举信号；三种情况都留 `AUTH_LOGIN_FAILED` 审计
 * （`operator_id` 为空、账号名取自输入，见 `V4__audit_log.sql` 的列注释）。
 *
 * <p><b>失败限流</b>（03 §16 的 SEC-5）：按「账号 + 客户端 IP」累计失败次数，达到阈值后临时锁定并返回
 * 1003/429；计数与判定见 {@link LoginAttemptLimiter}。
 *
 * <p><b>停用与降权即时生效</b>：这一层不在本类，而在 `JwtAuthenticationFilter`——它每次请求都按 `uid`
 * 回查账号状态与**当前角色**（03 §16 的 SEC-1、04-031）。本类只负责拦**新登录**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

  private final SysUserMapper sysUserMapper;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AuditFacade auditFacade;
  private final LoginAttemptLimiter loginAttemptLimiter;

  /** 登录成功后前端 `AuthSession` 需要的全部字段（10 §E2：token/name/roles/department）。 */
  public record LoginResult(String token, String name, Set<String> roles, String department) {}

  /**
   * 登录。**刻意不加 `@Transactional`**：登录没有业务写（只有一次读 + 一条审计），而失败路径是
   * 「先写 `AUTH_LOGIN_FAILED`、再抛 1002」——若整方法在一个事务里，抛异常会把刚写的失败审计一起回滚，
   * 结果是「登录失败审计实现了但一条也不落库」。实测证据：加 `@Transactional` 时 `audit_log` 里
   * 只有 `AUTH_LOGIN_SUCCESS`，`auto_increment` 比行数多 2（两次回滚）。
   *
   * <p>去掉注解后，审计由 `AuditFacade` 自己的事务提交（它在 `record*` 上有 `@Transactional`），
   * 失败审计因此能在业务失败后存活；成功路径没有别的写，拆成独立事务也不损失一致性
   * （05 §5.5 T6 要求的是「审计与业务写同事务」，此处的业务并没有写）。
   */
  public LoginResult login(String username, String rawPassword) {
    String clientIp = ClientIpResolver.current();

    if (loginAttemptLimiter.isLocked(username, clientIp)) {
      // 锁定期内不再写库：否则攻击者持续请求会把 audit_log 撑大。
      // 「进入锁定」的那一次已在 recordFailureAndMaybeLock 里留痕，这里只落 DEBUG。
      // 但**计数**要有：这是唯一能看到「有多少登录请求被限流挡在门外」的地方（03 §19 的 rejected 指标）。
      loginAttemptLimiter.recordRejection();
      log.debug("登录被限流（锁定期内）：账号={} ip={}", username, clientIp);
      throw new BizException(ErrorCode.LOGIN_TOO_MANY_ATTEMPTS);
    }

    SysUser user = sysUserMapper.findByUsernameForAuth(username).orElse(null);
    if (user == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
      recordFailureAndMaybeLock(username, clientIp);
      throw new BizException(ErrorCode.LOGIN_FAILED);
    }
    if (user.getStatus() != UserStatus.ENABLED) {
      recordFailureAndMaybeLock(username, clientIp);
      throw new BizException(ErrorCode.LOGIN_FAILED);
    }

    // 成功只清**账号**维度（03 §38 的 P1-5）：正常用户偶尔输错一次不会被记账；
    // 而 IP 计数让窗口自然衰减——在本机拓扑下它是全局 key，任何人撞中一次真口令就清零等于续命
    loginAttemptLimiter.reset(username, clientIp);

    Set<String> roles = Set.of(user.getRole().name());
    String department = user.getDepartment() == null ? null : user.getDepartment().name();
    String token =
        jwtService.issue(new JwtPayload(user.getId(), user.getUsername(), roles, department), Instant.now());

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("role", user.getRole().name());
    auditFacade.recordForOperator(
        user.getId(), user.getUsername(), AuditAction.AUTH_LOGIN_SUCCESS, null, null, detail);
    log.info("用户 {} 登录成功", user.getUsername());

    return new LoginResult(token, user.getName(), roles, department);
  }

  /**
   * 本人改口令（01 A3）：先校验原口令，再以**原哈希为前置条件**做守卫 UPDATE——
   * 校验与写入之间没有可被并发插队的窗口（06 §2.4 的取向，见 `SysUserMapper#changePassword`）。
   */
  @Transactional
  public void changeOwnPassword(String username, String oldPassword, String newPassword) {
    PasswordPolicy.validateOrThrow(newPassword);

    SysUser user =
        sysUserMapper
            .findByUsernameForAuth(username)
            // 认证已通过却查不到账号：令牌指向一个不存在的账号，按会话失效处理（前端回登录页）
            .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    String oldHash = user.getPasswordHash();
    if (!passwordEncoder.matches(oldPassword, oldHash)) {
      throw new BizException(ErrorCode.PASSWORD_MISMATCH);
    }
    if (sysUserMapper.changePassword(user.getId(), passwordEncoder.encode(newPassword), oldHash) == 0) {
      throw new BizException(ErrorCode.CONCURRENT_CONFLICT);
    }

    auditFacade.record(AuditAction.USER_PASSWORD_CHANGE, null, null, null);
    log.info("用户 {} 修改本人口令", username);
  }

  /** 登录失败留痕：操作人 id 未知（账号可能不存在），只记被尝试的账号名。 */
  private void recordLoginFailure(String attemptedUsername) {
    auditFacade.recordForOperator(
        null, attemptedUsername, AuditAction.AUTH_LOGIN_FAILED, null, null, null);
    log.info("登录失败：账号 {}", attemptedUsername);
  }

  /**
   * 记一次失败，并在**本次恰好进入锁定**时额外留一条 `AUTH_LOGIN_BLOCKED`。
   *
   * <p>只在这一个时刻写库（而不是锁定期内每次拒绝都写）：既保住「安全事件有留痕」，
   * 又不让持续爆破把 `audit_log` 撑大。
   */
  private void recordFailureAndMaybeLock(String attemptedUsername, String clientIp) {
    // ⚠️ 顺序：**先计数、后留痕**（03 §38 的 P1-8）。原先是反的——审计一抛异常（表故障、列宽、
    // 约束），`recordFailure` 就永不执行：留痕失败连带把限流一起关掉，且异常上浮成 5001
    // （不再是统一的 1002，等于给攻击者"这里出事了"的信号）。
    boolean locked = loginAttemptLimiter.recordFailure(attemptedUsername, clientIp);
    // 两条审计写**都要**包（只包第一条的话，进入锁定那一次照样 5001）。包的范围只限 auditFacade，
    // **不得顺手吞掉 BizException**。留痕失败降级为 ERROR 日志是安全边界的让渡（01 SM5 依赖审计
    // 完整性），经用户裁决（18 §6 点 8）；不引入异步（06 §2.8 红线）。
    try {
      recordLoginFailure(attemptedUsername);
    } catch (RuntimeException auditFailure) {
      log.error("登录失败留痕写入失败，已降级为日志：账号={} ip={}", attemptedUsername, clientIp, auditFailure);
    }
    if (locked) {
      try {
        auditFacade.recordForOperator(
            null, attemptedUsername, AuditAction.AUTH_LOGIN_BLOCKED, null, null, null);
      } catch (RuntimeException auditFailure) {
        log.error("进入锁定的留痕写入失败，已降级为日志：账号={} ip={}", attemptedUsername, clientIp, auditFailure);
      }
      log.warn("登录失败次数达到上限，账号临时锁定：账号={} ip={}", attemptedUsername, clientIp);
    }
  }
}
