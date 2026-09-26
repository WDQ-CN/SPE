package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Testcontainers 设施探针（10 §E6 与 11 §8.2 第 2 条）：先证明本机 Docker Desktop（WSL2）上
 * 能真正起容器、Flyway 能在其中跑完迁移，再写其余集成测试。
 *
 * <p>同时锁住「迁移在干净库上一次通过」这一 W1 完成判据：容器库是全新的，全部迁移必须 success。
 */
class TestcontainersSmokeTest extends AbstractIntegrationTest {

  @Autowired private Flyway flyway;

  @Test
  @DisplayName("干净库上迁移全部成功（数量不写死），且种子管理员写入成功")
  void migrationsApplyOnCleanDatabase() {
    // 不硬编码「已应用 6 条」「最后版本是 6」：新增一条 V7 就会让这条与本阶段无关的用例变红，
    // 那是维护噪音而不是行为回归。改为问 Flyway 自己——没有待应用的、也没有失败的
    assertThat(flyway.info().pending()).as("干净库上不应有未应用的迁移").isEmpty();
    assertThat(flyway.info().applied()).as("至少要跑过一条迁移").isNotEmpty();
    // 不用 flyway.info().failed()：该方法在 Flyway 11 的 MigrationInfoService 上已不存在，
    // 改为遍历全部迁移的 state（同一语义，且不把测试绑死在某个小版本的 API 上）
    assertThat(flyway.info().all())
        .as("不应有失败的迁移")
        .allSatisfy(migration -> assertThat(migration.getState().isFailed()).isFalse());

    Long admins =
        jdbcTemplate.queryForObject("select count(*) from sys_user where username = 'admin'", Long.class);
    assertThat(admins).isEqualTo(1L);
  }

  @Test
  @DisplayName("种子管理员的 BCrypt 哈希可用种子口令校验通过（E5 的兜底用例）")
  void seedAdminPasswordMatches() {
    // 用真实登录接口验证：口令与哈希对不上时本断言失败，而不是等到人工点登录才发现
    org.assertj.core.api.Assertions.assertThatCode(() -> login(ADMIN_USERNAME, ADMIN_PASSWORD))
        .doesNotThrowAnyException();
  }
}
