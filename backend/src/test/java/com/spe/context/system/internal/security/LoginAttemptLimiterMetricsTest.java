package com.spe.context.system.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 限流器指标（03 §19）：数值跟着行为走，且**不带任何标签**。
 *
 * <p>本类的重点是最后一条。账号名与来源 IP 都不能进标签——① 攻击者可以灌入海量随机账号，标签组合数
 * 随之爆炸，等于让被攻击者把监控也拖垮；② 「哪个账号正在被爆破」本身就是敏感信息，不该扩散到监控系统。
 * 把这条写成会失败的断言，而不是只写在注释里：将来有人"顺手加个 username 标签方便排查"就会红。
 */
class LoginAttemptLimiterMetricsTest {

  private static final String PREFIX = LoginAttemptLimiter.METRIC_PREFIX;

  private static final String USER = "zhangsan";
  private static final String IP = "203.0.113.7";

  private MutableClock clock;
  private SimpleMeterRegistry registry;
  private LoginAttemptLimiter limiter;

  @BeforeEach
  void setUp() {
    clock = new MutableClock();
    registry = new SimpleMeterRegistry();
    limiter = new LoginAttemptLimiter(clock, LoginLimiterProperties.defaults(), registry);
  }

  private double count(String name) {
    return registry.get(name).counter().count();
  }

  private double gauge(String name) {
    return registry.get(name).gauge().value();
  }

  @Test
  @DisplayName("失败与「进入锁定」分别计数：锁定只记一次，锁定期内的拒绝走 rejected")
  void failuresAndBlocksAreCountedSeparately() {
    for (int i = 0; i < LoginLimiterProperties.DEFAULT_MAX_FAILURES_PER_ACCOUNT; i++) {
      limiter.recordFailure(USER, IP);
    }
    assertThat(count(PREFIX + "failures")).isEqualTo(5.0);
    assertThat(count(PREFIX + "blocked")).as("只有恰好跨过阈值的那一次").isEqualTo(1.0);

    // 锁定期内：AuthService 只做 isLocked + recordRejection（按设计不写审计，所以这里是指标唯一的窗口）
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.isLocked(USER, IP)).isTrue();
      limiter.recordRejection();
    }
    assertThat(count(PREFIX + "rejected")).isEqualTo(3.0);
    assertThat(count(PREFIX + "failures")).as("被限流拒绝不算新的口令失败").isEqualTo(5.0);
  }

  @Test
  @DisplayName("条目数 Gauge 跟着内部 map 走——这是观测「封顶 + 摊还清理」是否在工作的唯一窗口")
  void trackedKeysGaugeFollowsInternalMap() {
    assertThat(gauge(PREFIX + "tracked.keys")).isZero();

    limiter.recordFailure("a", "10.0.0.1");
    assertThat(gauge(PREFIX + "tracked.keys")).isEqualTo(limiter.trackedKeys()).isEqualTo(2.0);

    limiter.recordFailure("b", "10.0.0.2");
    assertThat(gauge(PREFIX + "tracked.keys")).isEqualTo(4.0);
  }

  @Test
  @DisplayName("成功登录只清账号条目：IP 条目留着，Counter 单调不回退")
  void resetClearsGaugeButNotCounters() {
    limiter.recordFailure("a", "10.0.0.1");
    limiter.reset("a", "10.0.0.1");

    // 一次失败会建**两个**条目（账号 + IP）。03 §38 的 P1-5 之后 reset 只清账号那一个：
    // 本机拓扑下 IP 是全局 key，跟着成功登录一起清零等于给攻击者"撞中一次就续命"。
    assertThat(gauge(PREFIX + "tracked.keys")).as("只剩 IP 条目").isEqualTo(1.0);
    assertThat(limiter.isLocked("a", "10.0.0.1")).as("该账号的计数确实被清").isFalse();
    assertThat(count(PREFIX + "failures")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("窗口过后条目仍留着但已失效（Gauge 反映「存量」，不代表仍在锁定）")
  void gaugeReflectsStoredEntriesNotActiveLocks() {
    limiter.recordFailure(USER, IP);
    clock.advance(LoginLimiterProperties.DEFAULT_WINDOW.plusSeconds(1));

    assertThat(limiter.isLocked(USER, IP)).as("窗口已过，不再锁定").isFalse();
    assertThat(gauge(PREFIX + "tracked.keys"))
        .as("条目要等下一次达阈值清理时才消失，所以 Gauge 仍 >0 —— 这正是指标该表达的语义")
        .isGreaterThan(0.0);
  }

  @Test
  @DisplayName("指标不带任何标签：账号名与 IP 绝不进标签（基数安全 + 不扩散被攻击账号）")
  void metersCarryNoTags() {
    limiter.recordFailure("very-secret-account", IP);
    limiter.recordRejection();

    assertThat(registry.getMeters()).isNotEmpty();
    for (Meter meter : registry.getMeters()) {
      assertThat(meter.getId().getTags())
          .as("指标 %s 不应带任何标签（尤其不能是 username / clientIp）", meter.getId().getName())
          .isEmpty();
    }
    String ids =
        registry.getMeters().stream().map(meter -> meter.getId().toString()).reduce("", String::concat);
    assertThat(ids)
        .as("账号名与 IP 不能以任何形式出现在指标标识里")
        .doesNotContain("very-secret-account")
        .doesNotContain(IP);
  }

  /** 可手动推进的时钟：限流与窗口都跟时间相关，用真实等待测既慢又不稳。 */
  private static final class MutableClock extends Clock {

    private Instant now = Instant.parse("2026-09-26T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
