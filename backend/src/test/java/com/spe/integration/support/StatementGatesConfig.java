package com.spe.integration.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 并发与分片用例需要的三个 MyBatis 闸门（语句记录、停在语句之后、让第 N 次命中失败）。
 *
 * <p>⚠️ 代价要写明：多了这些 bean 的测试类会拿到**自己的上下文缓存条目**（多一次上下文启动）。
 * 用"注入拦截器造出确定交错"换"sleep 碰运气"是刻意的（06 §8 第 17 条：判据数语句、不数延迟）。
 */
@TestConfiguration(proxyBeanMethods = false)
public class StatementGatesConfig {

  @Bean
  public StatementRecorder statementRecorder() {
    return new StatementRecorder();
  }

  @Bean
  public ParkAfterStatementInterceptor parkAfterStatementInterceptor() {
    return new ParkAfterStatementInterceptor();
  }

  @Bean
  public FailOnNthStatementInterceptor failOnNthStatementInterceptor() {
    return new FailOnNthStatementInterceptor();
  }
}
