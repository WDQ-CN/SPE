package com.spe.context.system.internal.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
 * 故分成两个独立计数：① 账号维度挡「多 IP 爆破同一账号」；② IP 维度挡「同一 IP 撞库多个账号」。
 *
 * <p>⚠️ **IP 维度在本机的真实语义是"全局熔断"，不是"每个客户端一档"**（03 §38 的 P1-5）：
 * 单机 Docker Compose 下经 nginx 的请求在容器侧恒为同一个网桥地址，所有真实客户端共用一个 key；
 * 而 `forward-headers-strategy` 已改为 `none`（P1-4：采信 XFF 换不到真实 IP，只换来伪造面），
 * 所以这个维度只能当"整体异常时先挡一阵"的兜底，阈值因此从 20 放宽到 200。
 * 搬到能拿到真实源 IP 的环境时，这一档可以收紧回来——但必须先解决下面的伪造问题。
 *
 * <p><b>成功登录只清账号维度</b>：原先连 IP 键一起删，等于"任何人撞中一次真口令就重置全局计数"。
 * IP 计数让窗口自然衰减（{@link #reset}）。
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
 * <p><b>指标</b>（03 §19）：暴露条目数与三个计数（失败 / 进入锁定 / 锁定期内被拒），供
 * `/actuator/metrics` 与 `/actuator/prometheus` 读取。**这些指标刻意不带任何标签**，
 * 理由见 {@link #bindMetrics}。
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

  /** 指标前缀：`spe.` 用于和 JVM/HTTP 等自带指标区分。 */
  static final String METRIC_PREFIX = "spe.login.limiter.";

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

  private final Counter failures;
  private final Counter blocked;
  private final Counter rejected;

  /**
   * 生产构造器。
   *
   * <p><b>必须标 `@Autowired`</b>：本类有两个构造器（另一个是包可见的测试构造器，用于注入可控时钟），
   * 而 Spring 在「多个构造器且都没有 `@Autowired`」时会去找**无参**构造器——找不到就直接启动失败，
   * 报的是 `No default constructor found`，与本类真正的依赖无关，很容易看偏（04-044）。
   */
  @Autowired
  public LoginAttemptLimiter(LoginLimiterProperties properties, MeterRegistry meterRegistry) {
    this(Clock.systemUTC(), properties, meterRegistry);
  }

  /** 测试用：注入可控时钟；指标走真实的 registry（单测传 SimpleMeterRegistry 即可直接断言）。 */
  LoginAttemptLimiter(Clock clock, LoginLimiterProperties properties, MeterRegistry meterRegistry) {
    this.clock = clock;
    this.maxFailuresPerAccount = properties.maxFailuresPerAccount();
    this.maxFailuresPerIp = properties.maxFailuresPerIp();
    this.window = properties.window();
    this.maxEntries = properties.maxEntries();
    // 清理阈值跟着上限走：上限被设得很小（测试、或极端收紧的配置）时，若仍要等 1024 条才清理，
    // 就等于「上限先到、清理永不发生」——条目会一直占到上限才停，白留一批过期数据。
    this.pruneThreshold = Math.min(PRUNE_THRESHOLD, this.maxEntries);
    // 指标注册就写在构造器里（而不是抽成一个私有方法）：三个 Counter 都是 final 字段，而 Java 只允许
    // 在构造器中给 final 字段赋值——抽出去就只能改成非 final，或者多绕一个持有类。
    // 注册 Gauge 时传 this：Micrometer 只是保存引用，取值发生在抓取时，不会在构造期间回调本类。
    //
    // **为什么不加标签**：账号名与来源 IP 都不能做标签。① 攻击者可以灌入海量随机账号，标签组合数随之
    // 爆炸（基数是监控侧的硬约束，等于让被攻击者把监控也拖垮）；② 把「哪个账号正在被爆破」写进监控
    // 系统本身就是又一次信息扩散。要定位具体账号请查 `audit_log`——那里本来就有留痕。
    Gauge.builder(METRIC_PREFIX + "tracked.keys", this, LoginAttemptLimiter::trackedKeys)
        .description("当前记账条目数（账号维度 + IP 维度的 key 总数）；持续增长说明有账号名灌注")
        .register(meterRegistry);
    this.failures =
        Counter.builder(METRIC_PREFIX + "failures")
            .description("登录失败次数（账号不存在 / 口令错误 / 账号已停用，三者同码不区分）")
            .register(meterRegistry);
    this.blocked =
        Counter.builder(METRIC_PREFIX + "blocked")
            .description("因达到阈值而进入锁定的次数")
            .register(meterRegistry);
    this.rejected =
        Counter.builder(METRIC_PREFIX + "rejected")
            .description("锁定期内被直接拒绝的次数（按设计不写审计，故只能从这里看）")
            .register(meterRegistry);
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
    failures.increment();

    boolean locked = isLocked(username, clientIp);
    if (locked) {
      blocked.increment();
    }
    return locked;
  }

  /**
   * 记一次「锁定期内被拒绝」。单独一个方法而不是塞进 {@link #isLocked}：判断是幂等查询、计数是副作用，
   * 混在一起会让「谁调用了判断」变成隐式的计数来源——而只有真正要拒绝的那一次才算被拒。
   */
  public void recordRejection() {
    rejected.increment();
  }

  /** 登录成功：**只清账号维度**的计数，来源 IP 维度让其窗口自然衰减（理由见下）。 */
  public void reset(String username, String clientIp) {
    // ⚠️ **只清账号维度**（03 §38 的 P1-5）。原先这里连 IP 键一起删，而在单机部署下经 nginx 的
    // 所有客户端在容器侧塌缩成同一个对端地址——于是"任一次成功登录"就把**全局**的失败计数清零，
    // 攻击者偶尔撞中一个真口令就能续命整个锁定期。IP 计数让窗口自然衰减。
    attempts.remove(ACCOUNT_PREFIX + nullSafe(username));
  }

  /** 当前条目数：Gauge 的数据来源（可直接观测封顶与摊还清理是否在工作）。 */
  public int trackedKeys() {
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
