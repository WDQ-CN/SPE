package com.spe.context.system.internal.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流（03 §16 的 SEC-5）：**按账号与来源 IP 两个维度分别计数**，任一维度超阈值即锁定一段时间。
 *
 * <p><b>为什么需要</b>：登录接口此前没有任何频率限制，可被离线式爆破口令；而且每次失败都会写一条
 * `AUTH_LOGIN_FAILED` 审计——攻击顺带把 `audit_log` 撑大。
 *
 * <p><b>为什么是两个维度</b>：只按「账号 + IP」做复合键会被轻松绕过——换一个 IP 就能继续猜同一个账号。
 * 故分成两个独立计数：① 账号维度（5 次/窗口）挡「多 IP 爆破同一账号」；② IP 维度（20 次/窗口，阈值更宽，
 * 因为公司 NAT 后多个正常用户会共用一个出口 IP）挡「同一 IP 撞库多个账号」。
 *
 * <p><b>本地实现</b>：计数放在本进程的 `ConcurrentHashMap` 里，多实例部署时各算各的（演示项目可接受；
 * 生产应换成 Redis 计数或网关/WAF 限流，已登记 11 §9）。每次访问顺带清理过期条目，避免内存无界增长。
 *
 * <p><b>只统计失败</b>：成功登录会清掉两个维度的计数，对正常用户几乎无感。
 */
@Component
public class LoginAttemptLimiter {

  /** 账号维度阈值：挡「多 IP 爆破同一账号」。 */
  static final int MAX_FAILURES_PER_ACCOUNT = 5;

  /** IP 维度阈值：比账号维度宽松，避免 NAT 后的正常用户互相牵连。 */
  static final int MAX_FAILURES_PER_IP = 20;

  /** 统计窗口与锁定长度（自最近一次失败起算）。 */
  static final Duration WINDOW = Duration.ofMinutes(15);

  private static final String ACCOUNT_PREFIX = "u:";
  private static final String IP_PREFIX = "ip:";

  private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
  private final Clock clock;

  public LoginAttemptLimiter() {
    this(Clock.systemUTC());
  }

  /** 测试用：注入可控时钟，避免用例依赖真实时间流逝。 */
  LoginAttemptLimiter(Clock clock) {
    this.clock = clock;
  }

  /** 该次登录是否处于锁定期（账号维度或 IP 维度任一超限即锁）。 */
  public boolean isLocked(String username, String clientIp) {
    Instant now = clock.instant();
    return locked(ACCOUNT_PREFIX + nullSafe(username), MAX_FAILURES_PER_ACCOUNT, now)
        || locked(IP_PREFIX + nullSafe(clientIp), MAX_FAILURES_PER_IP, now);
  }

  /**
   * 记一次失败（两个维度都 +1）。
   *
   * @return 记录之后是否**已进入锁定**；调用方据此在「本次恰好进入锁定」时写一条 `AUTH_LOGIN_BLOCKED`，
   *     锁定期内的后续拒绝不再写库（否则攻击者持续请求会把审计表撑大）
   */
  public boolean recordFailure(String username, String clientIp) {
    Instant now = clock.instant();
    pruneExpired(now);
    bump(ACCOUNT_PREFIX + nullSafe(username), now);
    bump(IP_PREFIX + nullSafe(clientIp), now);
    return isLocked(username, clientIp);
  }

  /** 登录成功：清掉该账号与来源 IP 两个维度的计数。 */
  public void reset(String username, String clientIp) {
    attempts.remove(ACCOUNT_PREFIX + nullSafe(username));
    attempts.remove(IP_PREFIX + nullSafe(clientIp));
  }

  private void bump(String key, Instant now) {
    attempts.compute(
        key,
        (ignored, existing) ->
            existing == null || existing.expired(now)
                ? new Attempt(1, now)
                : new Attempt(existing.failures() + 1, now));
  }

  private boolean locked(String key, int maxFailures, Instant now) {
    Attempt attempt = attempts.get(key);
    return attempt != null && attempt.failures() >= maxFailures && !attempt.expired(now);
  }

  private void pruneExpired(Instant now) {
    attempts.entrySet().removeIf(entry -> entry.getValue().expired(now));
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  /** 单个维度的失败窗口：`windowStart` 取最近一次失败时刻，窗口内累计。 */
  private record Attempt(int failures, Instant windowStart) {

    boolean expired(Instant now) {
      return windowStart.plus(WINDOW).isBefore(now);
    }
  }
}
