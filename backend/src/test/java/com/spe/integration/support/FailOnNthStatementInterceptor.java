package com.spe.integration.support;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 测试专用的"让第 N 次命中的语句直接失败"闸门：用来证明**分片批次之间是同一个事务**
 * （03 §38 的 P1-9 风险控制——第 2 批失败时第 1 批必须一起回滚）。
 *
 * <p>为什么不用"造一条唯一键冲突的数据"来失败：那种办法要为了测试去构造脏数据，而且撞出来的
 * 是 {@code DuplicateKeyException}、会被发布路径自己的 try/catch 翻译成 3001，测不到"回滚"这件事。
 * 在拦截器里抛一个与业务无关的 {@link IllegalStateException}，才能证明回滚来自事务边界本身。
 *
 * <p>闸门一次性、且必须 {@link #disarm()}（用例失败也不能把它留在上下文里）。
 */
@Intercepts({
  @Signature(
      type = Executor.class,
      method = "update",
      args = {MappedStatement.class, Object.class}),
  @Signature(
      type = Executor.class,
      method = "query",
      args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class FailOnNthStatementInterceptor implements Interceptor {

  private static volatile Rule rule;

  /** 第 {@code nth} 次命中 {@code statementIdSuffix} 时抛错（从 1 数起，且必须在 {@code proceed()} 之前）。 */
  public static void arm(String statementIdSuffix, int nth) {
    rule = new Rule(statementIdSuffix, nth, new AtomicInteger());
  }

  public static void disarm() {
    rule = null;
  }

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    Rule current = rule;
    if (current != null
        && ((MappedStatement) invocation.getArgs()[0]).getId().endsWith(current.statementIdSuffix())
        && current.hits().incrementAndGet() == current.nth()) {
      rule = null;
      throw new IllegalStateException(
          "故意让第 " + current.nth() + " 次 " + current.statementIdSuffix() + " 失败（分片回滚用例）");
    }
    return invocation.proceed();
  }

  @Override
  public Object plugin(Object target) {
    return Plugin.wrap(target, this);
  }

  @Override
  public void setProperties(Properties properties) {
    // 这个闸门不需要配置项
  }

  private record Rule(String statementIdSuffix, int nth, AtomicInteger hits) {}
}
