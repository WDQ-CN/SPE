package com.spe.context.system.internal.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录限流参数（03 §16 的 SEC-5）。**外化成配置**而不是写死在代码里：阈值与窗口是运行期的策略，
 * 演示/测试/将来更严的环境需要的强度不同，改一个数字不该重新构建镜像。
 *
 * <p>每项都有代码默认值（{@link #DEFAULT_*}）：配置缺失时行为与之前完全一致，
 * 所以这是一次纯增强——不改变既有语义，只把旋钮交出来。
 *
 * <p>各项含义与默认值的理由：
 * <ul>
 *   <li>{@code maxFailuresPerAccount}：账号维度阈值，挡「多 IP 爆破同一账号」；
 *   <li>{@code maxFailuresPerIp}：IP 维度阈值，**故意比账号维度宽**——公司 NAT 后多个正常用户
 *       共用一个出口 IP，收得太紧会牵连无辜；
 *   <li>{@code window}：统计窗口与锁定长度（自最近一次失败起算）；
 *   <li>{@code maxEntries}：计数条目上限，防止「海量随机账号」把进程内存推高（见 {@link LoginAttemptLimiter}）。
 * </ul>
 */
@ConfigurationProperties(prefix = "app.login-limiter")
public record LoginLimiterProperties(
    Integer maxFailuresPerAccount, Integer maxFailuresPerIp, Duration window, Integer maxEntries) {

  public static final int DEFAULT_MAX_FAILURES_PER_ACCOUNT = 5;

  /**
   * IP 维度阈值。⚠️ 与 `application.yml` 的 `LOGIN_MAX_FAILURES_PER_IP` 默认值是同一事实的两处副本
   * （CLAUDE 第 3 条的待收敛项），改一处必须改另一处。
   *
   * <p>20 → 200（03 §38 的 P1-5）：单机 Compose 拓扑下经 nginx 的所有客户端在容器侧是同一个对端地址，
   * "每 IP 20 次"实际是**全局 20 次**——收得太紧等于给任何客户端一个"一键锁死所有人"的开关。
   */
  public static final int DEFAULT_MAX_FAILURES_PER_IP = 200;
  public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);
  public static final int DEFAULT_MAX_ENTRIES = 100_000;

  /** 紧凑构造器补齐缺省值：配置里少写一项就回落默认，而不是变成 null 以后在运行期炸掉。 */
  public LoginLimiterProperties {
    maxFailuresPerAccount = maxFailuresPerAccount == null ? DEFAULT_MAX_FAILURES_PER_ACCOUNT : maxFailuresPerAccount;
    maxFailuresPerIp = maxFailuresPerIp == null ? DEFAULT_MAX_FAILURES_PER_IP : maxFailuresPerIp;
    window = window == null ? DEFAULT_WINDOW : window;
    maxEntries = maxEntries == null ? DEFAULT_MAX_ENTRIES : maxEntries;
  }

  /** 全默认（测试与「什么都没配」的场景）。 */
  public static LoginLimiterProperties defaults() {
    return new LoginLimiterProperties(null, null, null, null);
  }
}
