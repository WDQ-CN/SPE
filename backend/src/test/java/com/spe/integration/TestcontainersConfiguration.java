package com.spe.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;

/**
 * 集成测试的 MySQL 容器（10 §E6）。容器作为一个 Spring Bean 被上下文缓存共享——同一批测试只起**一次**
 * 容器（每个测试类各起一次 MySQL 在本机要花十几秒，且会累积同名容器）。
 *
 * <p>镜像与 compose 同源（`mysql:8.4`，本机已缓存），`@ServiceConnection` 自动把连接信息
 * 注入 `spring.datasource.*`，测试里不必写死端口。
 *
 * <p>**为什么是静态单例而不是 `@Bean` 里 `new`**：一旦某个测试类替换了容器里的 Bean（如 C4 用
 * `@MockitoBean` 替换 `AuditFacade`），Spring 会为它建立**另一个上下文缓存条目**，`@Bean` 方法就会被
 * 再次调用、再起一个 MySQL 容器。静态实例保证所有上下文复用同一个库。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4").withDatabaseName("spe");

  static {
    MYSQL.start();
  }

  @Bean
  @ServiceConnection
  MySQLContainer<?> mysqlContainer() {
    return MYSQL;
  }
}
