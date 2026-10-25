package com.spe.integration.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
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
 * MyBatis 插件：按 **statement id** 记录每一条执行过的语句（06 §8 第 17 条"性能判据数 SQL 语句、不数延迟"）。
 *
 * <p>它是 {@code EvaluationApiIntegrationTest} 里那个语句计数器的**同一份实现**——原先两处各写一遍，
 * 新增用例的人会抄出第三个版本（CLAUDE 默认约定第 3 条）。放在测试支撑包里，只在 {@code @Import} 了
 * 它的上下文里生效。
 *
 * <p>⚠️ 必须带 {@code @Intercepts}：MyBatis 在 {@code openSession} 时会检查它，缺了直接抛
 * {@code PluginException: No @Intercepts annotation was found}。同时挂 {@code query} 与 {@code update}
 * 才能覆盖"读 + 写"两类调用。
 *
 * <p>⚠️ 计数发生在 {@code proceed()} **之前**：故意失败的那次调用也要被算进去（否则"第 2 批抛错"
 * 那条用例数不到自己的那次尝试）。
 */
@Intercepts({
  @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
  @Signature(
      type = Executor.class,
      method = "query",
      args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class StatementRecorder implements Interceptor {

  private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

  /** 某个 statement id 的后缀被命中的次数（如 {@code "SheetMapper.insertBatch"}）。 */
  public int countOf(String statementIdSuffix) {
    synchronized (executed) {
      return (int) executed.stream().filter(id -> id.endsWith(statementIdSuffix)).count();
    }
  }

  /** 本用例执行过的语句全 id，按执行顺序。 */
  public List<String> executedStatements() {
    synchronized (executed) {
      return List.copyOf(executed);
    }
  }

  public int totalCount() {
    return executed.size();
  }

  /** 归零。同一个 Spring 上下文里的用例共用一个单例，必须每条用例开头自己清。 */
  public void reset() {
    executed.clear();
  }

  @Override
  public Object intercept(Invocation invocation) throws Throwable {
    executed.add(((MappedStatement) invocation.getArgs()[0]).getId());
    return invocation.proceed();
  }

  @Override
  public Object plugin(Object target) {
    return Plugin.wrap(target, this);
  }

  @Override
  public void setProperties(Properties properties) {
    // 这个记录器不需要配置项
  }
}
