package com.spe.integration.support;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * "另一个连接确实被锁挡住了"的探针——并发用例的**等待判据**（03 §31.7.14 的 P2-8 修在这里）。
 *
 * <p>从 {@code TemplateConcurrencyIntegrationTest} 提到支撑包：发布的串行化用例（P1-15）与模板的
 * 并发用例要证的是同一件事，两处各写一遍就会有一个版本先腐烂。
 *
 * <p>⚠️ 为什么是 `SHOW FULL PROCESSLIST` 而不是 `performance_schema.data_lock_waits`：实测测试容器的
 * 账号权限只有 `GRANT PROCESS`（为并发用例特意加的，见 {@code TestcontainersConfiguration}）而没有
 * `performance_schema` 的读权限，`information_schema.innodb_trx` 同样拿不到——processlist 是这个账号
 * **唯一**能看到的"谁在等锁"视图（同账号的其它连接可见，且 `SHOW FULL` 不截断 `Info`）。
 */
public final class LockWaitProbe {

  private final JdbcTemplate jdbcTemplate;

  public LockWaitProbe(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 等"**另一个连接确实被锁挡住**"这件事发生，返回那条被挡住的语句（小写、空白已压缩）。
   *
   * <p>判据是"**同一条语句在相隔 100ms 的两次采样里都还停在 processlist 上**"——跑得快的语句不可能被
   * 连续两次采样抓到。这不是"睡够 500ms 就该好了"那种猜测。超时**抛错**（失败，不是默默放行）：
   * 没复现出并发场景时用例必须响亮地红，而不是退化成串行后假装通过。
   */
  public String awaitBlockedStatement(Duration timeout) throws InterruptedException {
    Long myConnectionId = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
    long deadline = System.nanoTime() + timeout.toNanos();
    String previous = null;
    while (System.nanoTime() < deadline) {
      String current = blockedStatement(myConnectionId);
      if (current != null && current.equals(previous)) {
        return current;
      }
      previous = current;
      Thread.sleep(100); // 等待的**轮询间隔**：判据见方法注释，不是"睡够就算好了"
    }
    throw new AssertionError(
        "超时（"
            + timeout.toSeconds()
            + "s）没等到任何连接被锁挡住——并发场景没复现出来，用例已退化成串行，不能算通过。"
            + "最后一次采样看到的是："
            + previous);
  }

  /** 当前被挡住的语句（排除我们自己的采样连接与采样语句本身）；没有则返回 null。 */
  public String blockedStatement(Long excludeConnectionId) {
    for (Map<String, Object> row : jdbcTemplate.queryForList("SHOW FULL PROCESSLIST")) {
      Object id = row.get("Id");
      if (excludeConnectionId != null
          && id instanceof Number
          && ((Number) id).longValue() == excludeConnectionId) {
        continue; // 我们自己的采样语句
      }
      Object info = row.get("Info");
      if (info == null) {
        continue; // Sleep 状态的空闲连接：没有正在执行的语句，不算"被挡住"
      }
      String sql = info.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
      if (!sql.startsWith("show ") && !sql.contains("processlist")) {
        return sql;
      }
    }
    return null;
  }
}
