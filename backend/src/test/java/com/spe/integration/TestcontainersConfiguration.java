package com.spe.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

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
 *
 * <p>⚠️⚠️ **测试账号必须是"与生产一致的最小权限"**（04-079）：Testcontainers 默认给账号
 * `GRANT ALL ON <db>.*`，而生产实测只有
 * `SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, REFERENCES, INDEX, ALTER ON spe.*`
 * （用 `SHOW GRANTS FOR 'spe_app'@'%'` 查过）。这个**差集**里就包含 `CREATE TEMPORARY TABLES`——
 * `V9` 的守卫一度用了临时表，结果**测试全绿、真实部署 `Access denied` ⇒ 迁移失败、后端起不来**（04-077）。
 * 收窄之后，"迁移里用了应用账号没有的权限"这类问题在 `mvn test` 阶段就会红。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  private static final MySQLContainer MYSQL =
      new MySQLContainer("mysql:8.4")
          .withDatabaseName("spe")
          // 给测试容器也设内存上限。宿主 Docker 引擎的内存上限约 7.8 GB，而 MySQL 默认按宿主机内存
          // 自行放大缓冲池；连续跑多轮全量测试时，无限制的测试容器叠加应用容器曾把引擎压垮
          // （现象是 Docker Desktop 引擎整个停掉、所有容器一起消失，见 04-043）。512 MB 够跑本项目用例。
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024));

  static {
    MYSQL.start();
    restrictToLeastPrivilege();
  }

  /**
   * 把测试账号收成**与生产一致的最小权限**（04-079），并**自证收窄真的生效**。
   *
   * <p>必须在 Spring 上下文起来**之前**执行——Flyway 是应用启动时用这个账号跑的，`04-077` 的失败正是发生
   * 在那一步，晚于上下文就没有意义了。
   *
   * <p>用户名与口令一律从容器 API 取（`getUsername()` / `getEnvMap()`），**不写字面量**。
   */
  private static void restrictToLeastPrivilege() {
    String user = MYSQL.getUsername();
    String rootPassword = MYSQL.getEnvMap().getOrDefault("MYSQL_ROOT_PASSWORD", MYSQL.getPassword());
    List<String> statements =
        List.of(
            // 先收掉 Testcontainers 默认给的 ALL，再按生产实测的集合逐条授予
            "REVOKE ALL PRIVILEGES ON `spe`.* FROM '" + user + "'@'%'",
            "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, REFERENCES, INDEX, ALTER"
                + " ON `spe`.* TO '"
                + user
                + "'@'%'",
            // ⚠️ 比生产多这一个：并发用例的等待判据要读**别的连接**（`SHOW FULL PROCESSLIST`），
            //    没有 PROCESS 只看得到自己。属**测试专用**；迁移里的 `performance_schema` 仍由
            //    check-conventions 的 migration-privileges 拦（生产账号没有 PROCESS）。
            "GRANT PROCESS ON *.* TO '" + user + "'@'%'",
            "FLUSH PRIVILEGES");
    try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", rootPassword);
        Statement statement = root.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(
          "收窄测试账号权限失败 —— 宁可让测试起不来，也不要让测试环境悄悄退化成权限过剩（04-079）", ex);
    }
    // 自证（CLAUDE 默认约定第 1 条：不能只"声明"）：收窄必须真的生效——若还能建临时表，说明上面那句
    // REVOKE 没起作用，测试环境会静默退回"权限过剩"，那套保护就成了摆设。
    // ⚠️⚠️ 这个探针必须用**应用账号**去跑，不能用 root —— 第一版我就是在 root 连接上执行的，于是它
    //      永远"失败"并把自己那套自证炸掉（04-077 的同一教训：**身份不对，验证无效**）。
    try (Connection app = DriverManager.getConnection(MYSQL.getJdbcUrl(), user, MYSQL.getPassword());
        Statement appStatement = app.createStatement()) {
      appStatement.execute("CREATE TEMPORARY TABLE least_privilege_probe (n INT)");
      throw new IllegalStateException(
          "权限收窄**没生效**：测试账号仍能建临时表 ⇒ 测试环境与生产不一致（04-079）");
    } catch (SQLException expected) {
      // 期望路径：Access denied for ... CREATE TEMPORARY TABLES
    }
  }

  @Bean
  @ServiceConnection
  MySQLContainer mysqlContainer() {
    return MYSQL;
  }
}
