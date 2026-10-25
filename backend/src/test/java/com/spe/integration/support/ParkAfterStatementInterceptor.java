package com.spe.integration.support;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 测试专用的"停在某条语句之后"的闸门（并发用例的确定性交错，06 §8 第 17 条禁无理由 sleep）。
 *
 * <p><b>为什么必须注入拦截器，而不是"两条线程一起冲"</b>：要证的交错只有几条语句宽，
 * 靠调度撞不出来；靠 sleep 等则既慢又假。所以让指定前缀的工作线程在命中目标语句后停住，
 * 测试线程趁这个确定的点去操作另一个事务，再放行——**红了就是因为判据，不是因为运气**。
 *
 * <p>⚠️ 停在 {@code proceed()} **之后**，也就是"这条语句的效果已经生效"那一刻。对发布路径来说
 * 这个区别就是全部意义：停在 {@code lockAllTasksByIdAsc} 之后 = 停在"整表 X 锁已经到手"之后，
 * 此时另一条线程发起的发布必须**在数据库里排队**，这正是 P1-15 根治要证的东西。
 *
 * <p>闸门是**一次性**的（命中即解除），所以同事务内的第二次读与其它用例都不受影响；
 * 每个用了它的测试类都要在 {@code @AfterEach} 里调 {@link #disarm()}，免得用例失败后把线程留在闸门里。
 */
@Intercepts({
  @Signature(
      type = Executor.class,
      method = "query",
      args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
  @Signature(
      type = Executor.class,
      method = "query",
      args = {
        MappedStatement.class,
        Object.class,
        RowBounds.class,
        ResultHandler.class,
        CacheKey.class,
        BoundSql.class
      })
})
public class ParkAfterStatementInterceptor implements Interceptor {

  /** 当前生效的闸门；{@code null} = 不拦任何语句。 */
  private static volatile Rule rule;

  /**
   * 让**线程名前缀匹配**的工作线程在 {@code statementIdSuffix} 执行完之后停一次。
   *
   * <p>必须按线程名过滤：主线程也要发布（造前置数据），只按语句 id 拦会把主线程一起停住、直接锁死测试。
   */
  public static Gate arm(String statementIdSuffix, String threadNamePrefix) {
    Gate gate = new Gate();
    rule = new Rule(statementIdSuffix, threadNamePrefix, gate);
    return gate;
  }

  public static void disarm() {
    rule = null;
  }

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Object result = invocation.proceed();
    Rule current = rule;
    if (current != null
        && ((MappedStatement) invocation.getArgs()[0]).getId().endsWith(current.statementIdSuffix())
        && Thread.currentThread().getName().startsWith(current.threadNamePrefix())) {
      rule = null;
      current.gate().markParked();
      current.gate().awaitResume();
    }
    return result;
  }

  @Override
  public Object plugin(Object target) {
    return Plugin.wrap(target, this);
  }

  @Override
  public void setProperties(Properties properties) {
    // 这个闸门不需要配置项
  }

  private record Rule(String statementIdSuffix, String threadNamePrefix, Gate gate) {}

  /** 工作线程与测试线程之间的两次握手：parked ⇒ resume。 */
  public static final class Gate {

    private static final long TIMEOUT_SECONDS = 30;

    private final CountDownLatch parked = new CountDownLatch(1);
    private final CountDownLatch resume = new CountDownLatch(1);

    void markParked() {
      parked.countDown();
    }

    /** 工作线程确实停在闸门里了吗？没停就是**失败**（不许退化成"串行跑完假装通过"）。 */
    public void awaitParked() throws InterruptedException {
      if (!parked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
            "工作线程没有在 " + TIMEOUT_SECONDS + "s 内停进闸门——交错没造出来，本用例的结论不成立");
      }
    }

    void awaitResume() throws InterruptedException {
      if (!resume.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("闸门超时未放行，工作线程被留在事务里");
      }
    }

    /** 放行工作线程（它的本地变量 {@code result} 已经拿到，事务仍然开着）。 */
    public void resume() {
      resume.countDown();
    }
  }
}
