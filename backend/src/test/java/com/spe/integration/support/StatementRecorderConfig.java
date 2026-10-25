package com.spe.integration.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 把 {@link StatementRecorder} 注册进本测试类的 Spring 上下文。
 *
 * <p>mybatis-spring-boot-starter 会自动把容器里的 {@code Interceptor} bean 挂到 SqlSessionFactory 上，
 * 所以只需要一个 bean 声明；代价是**多了这个 bean 的测试类会拿到自己的上下文缓存条目**
 * （多一次上下文启动），这是"能数到语句"的必要成本，写在 {@code 11 §9} 的耗时口径里。
 */
@TestConfiguration(proxyBeanMethods = false)
public class StatementRecorderConfig {

  @Bean
  public StatementRecorder statementRecorder() {
    return new StatementRecorder();
  }
}
