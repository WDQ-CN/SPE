package com.spe.context.system.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 登录失败限流的阈值、窗口与算法边界（03 §16 的 SEC-5、§18 的算法优化）。
 *
 * <p>用**可手动推进的时钟**测，不靠 `Thread.sleep`：限流是时间相关行为，用真实等待既慢又不稳。
 *
 * <p>覆盖三件事：① 两个维度各自独立——只按「账号 + IP」复合键计数时，换一个 IP 就能继续爆破同一账号；
 * ② 阈值与窗口的边界；③ **算法边界**——条目封顶与摊还清理。后两条是 P1 收口时补的：早期实现每次失败
 * 都扫全表清理，攻击者用海量互不相同的账号名就能把成本推成 O(n²)，同时把内存撑大。
 */
class LoginAttemptLimiterTest {

  private static final String USER = "zhangsan";
  private static final String IP = "203.0.113.7";
  private static final int ACCOUNT_LIMIT = LoginLimiterProperties.DEFAULT_MAX_FAILURES_PER_ACCOUNT;
  private static final int IP_LIMIT = LoginLimiterProperties.DEFAULT_MAX_FAILURES_PER_IP;

  private MutableClock clock;
  private LoginAttemptLimiter limiter;

  @BeforeEach
  void setUp() {
    clock = new MutableClock();
    limiter = new LoginAttemptLimiter(clock, LoginLimiterProperties.defaults(), new SimpleMeterRegistry());
  }

  @Test
  @DisplayName("账号维度：失败 4 次不锁，第 5 次起锁定")
  void accountDimensionLocksAtThreshold() {
    for (int i = 0; i < ACCOUNT_LIMIT - 1; i++) {
      assertThat(limiter.recordFailure(USER, IP)).as("第 %d 次失败", i + 1).isFalse();
      assertThat(limiter.isLocked(USER, IP)).isFalse();
    }

    assertThat(limiter.recordFailure(USER, IP)).isTrue();
    assertThat(limiter.isLocked(USER, IP)).isTrue();
  }

  @Test
  @DisplayName("换 IP 不能绕过账号维度：同一账号在另一个 IP 上依然被锁")
  void changingIpDoesNotBypassAccountLimit() {
    for (int i = 0; i < ACCOUNT_LIMIT; i++) {
      limiter.recordFailure(USER, IP);
    }

    assertThat(limiter.isLocked(USER, IP)).isTrue();
    assertThat(limiter.isLocked(USER, "198.51.100.9")).as("换 IP 仍被账号维度挡住").isTrue();
  }

  @Test
  @DisplayName("IP 维度：同一 IP 撞库多个账号，达到 IP 阈值后所有账号都被锁")
  void ipDimensionLocksAfterManyAccounts() {
    for (int i = 0; i < IP_LIMIT - 1; i++) {
      limiter.recordFailure("victim-" + i, IP);
    }
    assertThat(limiter.isLocked("victim-0", IP)).isFalse();

    assertThat(limiter.recordFailure("victim-last", IP)).isTrue();
    // 每个账号各自只失败一次，但该 IP 整体被锁
    assertThat(limiter.isLocked("victim-0", IP)).isTrue();
    assertThat(limiter.isLocked("a-brand-new-account", IP)).isTrue();
  }

  @Test
  @DisplayName("成功登录清零：不影响之后再失败几次")
  void successResetsCounters() {
    for (int i = 0; i < ACCOUNT_LIMIT - 1; i++) {
      limiter.recordFailure(USER, IP);
    }

    limiter.reset(USER, IP);

    assertThat(limiter.isLocked(USER, IP)).isFalse();
    for (int i = 0; i < ACCOUNT_LIMIT - 1; i++) {
      assertThat(limiter.recordFailure(USER, IP)).isFalse();
    }
  }

  @Test
  @DisplayName("窗口过后自动解锁（不需要人工清理）")
  void lockExpiresAfterWindow() {
    for (int i = 0; i < ACCOUNT_LIMIT; i++) {
      limiter.recordFailure(USER, IP);
    }
    assertThat(limiter.isLocked(USER, IP)).isTrue();

    clock.advance(LoginLimiterProperties.DEFAULT_WINDOW.plusSeconds(1));

    assertThat(limiter.isLocked(USER, IP)).isFalse();
  }

  @Test
  @DisplayName("账号或 IP 缺失时不抛异常（无请求上下文时 ClientIpResolver 会返回 null）")
  void toleratesMissingKeys() {
    assertThat(limiter.isLocked(null, null)).isFalse();
    assertThat(limiter.recordFailure(null, null)).isFalse();
    limiter.reset(null, null);
  }

  @Test
  @DisplayName("条目封顶：达到上限后不再为新 key 建档，但已有的 key 仍继续计数")
  void entriesAreCappedWithoutLosingExistingCounters() {
    // 用极小的上限验证封顶行为（生产默认十万条：既测不起，也不该在单测里灌十万次）
    LoginAttemptLimiter capped =
        new LoginAttemptLimiter(
            clock,
            new LoginLimiterProperties(ACCOUNT_LIMIT, IP_LIMIT, Duration.ofMinutes(15), 3),
            new SimpleMeterRegistry());

    // 灌入 10 个互不相同的账号：条目数停在上限附近，不会随攻击无界增长
    for (int i = 0; i < 10; i++) {
      capped.recordFailure("flood-" + i, "203.0.113.1");
    }
    assertThat(capped.trackedKeys()).as("条目数被上限挡住").isLessThanOrEqualTo(6);

    // 已有 key 仍在计数：同一账号继续失败到阈值就该锁——封顶不该削弱真正的限流
    for (int i = 0; i < ACCOUNT_LIMIT; i++) {
      capped.recordFailure("flood-0", "203.0.113.1");
    }
    assertThat(capped.isLocked("flood-0", "203.0.113.1")).isTrue();
  }

  @Test
  @DisplayName("过期条目会被清理：到达清理阈值时扫一遍，map 不随攻击一直长")
  void expiredEntriesArePrunedOnceThresholdIsReached() {
    LoginAttemptLimiter small =
        new LoginAttemptLimiter(
            clock,
            new LoginLimiterProperties(ACCOUNT_LIMIT, IP_LIMIT, Duration.ofMinutes(15), 3),
            new SimpleMeterRegistry());

    small.recordFailure("a", "10.0.0.1");
    small.recordFailure("b", "10.0.0.2");
    small.recordFailure("c", "10.0.0.3");
    int before = small.trackedKeys();
    assertThat(before).isGreaterThan(1);

    clock.advance(LoginLimiterProperties.DEFAULT_WINDOW.plusSeconds(1));
    small.recordFailure("d", "10.0.0.4");

    assertThat(small.trackedKeys()).as("过期的旧条目被清掉，只剩本次这一对").isLessThan(before);
    assertThat(small.isLocked("a", "10.0.0.1")).as("窗口过后旧计数不再生效").isFalse();
  }

  /** 可手动推进的时钟：限流行为与时间强相关，用真实 sleep 测既慢又不稳定。 */
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
