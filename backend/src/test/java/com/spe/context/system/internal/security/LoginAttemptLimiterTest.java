package com.spe.context.system.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 登录失败限流的阈值与窗口（03 §16 的 SEC-5）。
 *
 * <p>用**可手动推进的时钟**测，不靠 `Thread.sleep`：限流是时间相关行为，用真实等待既慢又不稳。
 * 重点覆盖两个维度的独立性——只按「账号 + IP」复合键计数时，换一个 IP 就能继续爆破同一账号。
 */
class LoginAttemptLimiterTest {

  private static final String USER = "zhangsan";
  private static final String IP = "203.0.113.7";

  private MutableClock clock;
  private LoginAttemptLimiter limiter;

  @BeforeEach
  void setUp() {
    clock = new MutableClock();
    limiter = new LoginAttemptLimiter(clock);
  }

  @Test
  @DisplayName("账号维度：失败 4 次不锁，第 5 次起锁定")
  void accountDimensionLocksAtThreshold() {
    for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES_PER_ACCOUNT - 1; i++) {
      assertThat(limiter.recordFailure(USER, IP)).as("第 %d 次失败", i + 1).isFalse();
      assertThat(limiter.isLocked(USER, IP)).isFalse();
    }

    assertThat(limiter.recordFailure(USER, IP)).isTrue();
    assertThat(limiter.isLocked(USER, IP)).isTrue();
  }

  @Test
  @DisplayName("换 IP 不能绕过账号维度：同一账号在另一个 IP 上依然被锁")
  void changingIpDoesNotBypassAccountLimit() {
    for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES_PER_ACCOUNT; i++) {
      limiter.recordFailure(USER, IP);
    }

    assertThat(limiter.isLocked(USER, IP)).isTrue();
    assertThat(limiter.isLocked(USER, "198.51.100.9")).as("换 IP 仍被账号维度挡住").isTrue();
  }

  @Test
  @DisplayName("IP 维度：同一 IP 撞库多个账号，达到 IP 阈值后所有账号都被锁")
  void ipDimensionLocksAfterManyAccounts() {
    for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES_PER_IP - 1; i++) {
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
    for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES_PER_ACCOUNT - 1; i++) {
      limiter.recordFailure(USER, IP);
    }

    limiter.reset(USER, IP);

    assertThat(limiter.isLocked(USER, IP)).isFalse();
    for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES_PER_ACCOUNT - 1; i++) {
      assertThat(limiter.recordFailure(USER, IP)).isFalse();
    }
  }

  @Test
  @DisplayName("窗口过后自动解锁（不需要人工清理）")
  void lockExpiresAfterWindow() {
    for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES_PER_ACCOUNT; i++) {
      limiter.recordFailure(USER, IP);
    }
    assertThat(limiter.isLocked(USER, IP)).isTrue();

    clock.advance(LoginAttemptLimiter.WINDOW.plusSeconds(1));

    assertThat(limiter.isLocked(USER, IP)).isFalse();
  }

  @Test
  @DisplayName("账号或 IP 缺失时不抛异常（无请求上下文时 ClientIpResolver 会返回 null）")
  void toleratesMissingKeys() {
    assertThat(limiter.isLocked(null, null)).isFalse();
    assertThat(limiter.recordFailure(null, null)).isFalse();
    limiter.reset(null, null);
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
