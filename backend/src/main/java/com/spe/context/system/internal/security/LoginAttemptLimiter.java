package com.spe.context.system.internal.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流（03 §16 的 SEC-5）：**按账号与来源 IP 两个维度分别计数**，任一维度超阈值即锁定一段时间。
 *
 * <p><b>为什么需要</b>：登录接口此前没有任何频率限制，可被离线式爆破口令；而且每次失败都会写一条
 * `AUTH_LOGIN_FAILED` 审计——攻击顺带把 `audit_log` 撑大。
 *
 * <p><b>为什么是两个维度</b>：只按「账号 + IP」做复合键会被轻松绕过——换一个 IP 就能继续猜同一个账号。
 * 故分成两个独立计数：① 账号维度挡「多 IP 爆破同一账号」；② IP 维度（阈值更宽，因为公司 NAT 后
 * 多个正常用户会共用一个出口 IP）挡「同一 IP 撞库多个账号」。
 *
 * <p><b>算法</b>（P1 收口后的优化，见 03 §18）：早期实现每次失败都 `removeIf` 扫全表清理过期条目，
 * 于是成本是「每次 O(条目数)」——攻击者用**海量互不相同的账号名**灌注，就能把总成本推成 O(n²)，
 * 同时把内存撑大。现在改成两条：
 * <ol>
 *   <li>**摊还清理**：只在条目数超过阈值时清理一次，而不是每次都扫；
 *   <li>**条目封顶**：达到上限后不再为新 key 建档（对**已有** key 继续计数）。
 * </ol>
 * 后者不影响真正的攻击：同一账号或同一 IP 的攻击用的是已有 key，而且会先撞上 IP 维度阈值——
 * 要灌满十万条随机账号，得先有十万个不同来源 IP，那时瓶颈早已不在本进程。
 *
 * <p><b>本地实现</b>：计数放在本进程的 `ConcurrentHashMap` 里，多实例部署时各算各的（演示项目可接受；
 * 生产应换成 Redis 计数或网关/WAF 限流，已登记 11 §9）。
 *
 * <p><b>只统计失败</b>：成功登录会清掉两个维度的计数，对正常用户几乎无感。
 */
@Component
public class LoginAttemptLimiter {

  /** 条目数超过它时触发一次全量清理（摊还到每次操作上仍是 O(1)）。 */
  static final int PRUNE_THRESHOLD = 1_024;

  private static final String ACCOUNT_PREFIX = "u:";
  private static final String IP_PREFIX = "ip:";

  private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
  private final Clock clock;
  private final int maxFailuresPerAccount;
  private final int maxFailuresPerIp;
  private final Duration window;
  private final int maxEntries;
  /** 条目数达到它就清理一次过期项；实际取 {@code min(PRUNE_THRESHOLD, maxEntries)}。 */
  private final int pruneThreshold;

  /**
   * 生产构造器。
   *
   * <p><b>必须标 `@Autowired`</b>：本类有两个构造器（另一个是包可见的测试构造器，用于注入可控时钟），
   * 而 Spring 在「多个构造器且都没有 `@Autowired`」时会去找**无参**构造器——找不到就直接启动失败，
   * 报的是 `No default constructor found`，与本类真正的依赖无关，很容易看偏（P1 收口后踩过）。
   */
  @Autowired
  public LoginAttemptLimiter(LoginLimiterProperties properties) {
    this(Clock.systemUTC(), properties);
  }

  /** 测试用：注入可控时钟，避免用例依赖真实时间流逝。 */
  LoginAttemptLimiter(Clock clock, LoginLimiterProperties properties) {
    this.clock = clock;
    this.maxFailuresPerAccount = properties.maxFailuresPerAccount();
    this.maxFailuresPerIp = properties.maxFailuresPerIp();
    this.window = properties.window();
    this.maxEntries = properties.maxEntries();
    // 清理阈值跟着上限走：上限被设得很小（测试、或极端收紧的配置）时，若仍要等 1024 条才清理，
    // 就等于「上限先到、清理永不发生」——条目会一直占到上限才停，白留一批过期数据。
    this.pruneThreshold = Math.min(PRUNE_THRESHOLD, this.maxEntries);
  }

  /** 该次登录是否处于锁定期（账号维度或 IP 维度任一超限即锁）。 */
  public boolean isLocked(String username, String clientIp) {
    Instant now = clock.instant();
    return locked(ACCOUNT_PREFIX + nullSafe(username), maxFailuresPerAccount, now)
        || locked(IP_PREFIX + nullSafe(clientIp), maxFailuresPerIp, now);
  }

  /**
   * 记一次失败（两个维度都 +1）。
   *
   * @return 记录之后是否**已进入锁定**；调用方据此在「本次恰好进入锁定」时写一条 `AUTH_LOGIN_BLOCKED`，
   *     锁定期内的后续拒绝不再写库（否则攻击者持续请求会把审计表撑大）
   */
  public boolean recordFailure(String username, String clientIp) {
    Instant now = clock.instant();
    if (attempts.size() >= pruneThreshold) {
      pruneExpired(now);
    }
    bump(ACCOUNT_PREFIX + nullSafe(username), now);
    bump(IP_PREFIX + nullSafe(clientIp), now);
    return isLocked(username, clientIp);
  }

  /** 登录成功：清掉该账号与来源 IP 两个维度的计数。 */
  public void reset(String username, String clientIp) {
    attempts.remove(ACCOUNT_PREFIX + nullSafe(username));
    attempts.remove(IP_PREFIX + nullSafe(clientIp));
  }

  /** 当前条目数，仅供测试与观测（可用于暴露为指标）。 */
  int trackedKeys() {
    return attempts.size();
  }

  private void bump(String key, Instant now) {
    // 封顶后不再为新 key 建档：对已有 key 仍然计数（见类注释「算法」一节）
    if (attempts.size() >= maxEntries && !attempts.containsKey(key)) {
      return;
    }
    attempts.compute(
        key,
        (ignored, existing) ->
            existing == null || expired(existing, now)
                ? new Attempt(1, now)
                : new Attempt(existing.failures() + 1, now));
  }

  private boolean locked(String key, int maxFailures, Instant now) {
    Attempt attempt = attempts.get(key);
    return attempt != null && attempt.failures() >= maxFailures && !expired(attempt, now);
  }

  private void pruneExpired(Instant now) {
    attempts.entrySet().removeIf(entry -> expired(entry.getValue(), now));
  }

  /** 窗口判定放在外层：{@link Attempt} 是静态嵌套记录，读不到实例字段 {@code window}。 */
  private boolean expired(Attempt attempt, Instant now) {
    return attempt.windowStart().plus(window).isBefore(now);
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  /** 单个维度的失败窗口：`windowStart` 取最近一次失败时刻，窗口内累计。 */
  private record Attempt(int failures, Instant windowStart) {}
}
