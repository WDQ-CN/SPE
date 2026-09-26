package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testcontainers 设施探针（10 §E6 与 11 §8.2 第 2 条）：先证明本机 Docker Desktop（WSL2）上
 * 能真正起容器、Flyway 能在其中跑完迁移，再写其余集成测试。
 *
 * <p>同时锁住「迁移在干净库上一次通过」这一 W1 完成判据：容器库是全新的，V1~V6 必须全部 success。
 */
class TestcontainersSmokeTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("干净库上迁移全绿，且种子管理员写入成功")
  void migrationsApplyOnCleanDatabase() {
    Integer applied =
        jdbcTemplate.queryForObject(
            "select count(*) from flyway_schema_history where success = 1", Integer.class);
    assertThat(applied).isEqualTo(6);

    String version =
        jdbcTemplate.queryForObject(
            "select version from flyway_schema_history order by installed_rank desc limit 1", String.class);
    assertThat(version).isEqualTo("6");

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
