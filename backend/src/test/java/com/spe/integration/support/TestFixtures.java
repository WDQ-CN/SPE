package com.spe.integration.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试的建数据夹具（03 §51 P3-5）：`sys_user` / `supplier` 的 INSERT 列清单与默认状态只在这里。
 *
 * <p>各测试类仍可保留带业务语义的轻量包装（不同的 category/role/department/name），但 SQL 不再各写一份。
 * 口令哈希是公开演示口令的固定 BCrypt 值：这些用户既做审批人/评估人，也会被直接 `login` 用于 HTTP 用例。
 */
public final class TestFixtures {

  /** 公开演示口令的固定 BCrypt 值；改动会让依赖直接 `login` 的用例失效。 */
  public static final String PASSWORD_HASH =
      "$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm";

  private TestFixtures() {}

  public static long insertSupplier(
      JdbcTemplate jdbcTemplate, String code, String name, String category, String status) {
    jdbcTemplate.update(
        "INSERT INTO supplier (code, name, category, status, version) VALUES (?, ?, ?, ?, 0)",
        code,
        name,
        category,
        status);
    Long id =
        jdbcTemplate.queryForObject("SELECT id FROM supplier WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException("供应商插入后取不到 id：" + code);
    }
    return id;
  }

  public static long insertSupplier(
      JdbcTemplate jdbcTemplate, String code, String name, String category) {
    return insertSupplier(jdbcTemplate, code, name, category, "ENABLED");
  }

  public static long insertSupplier(JdbcTemplate jdbcTemplate, String code, String name) {
    return insertSupplier(jdbcTemplate, code, name, "测试", "ENABLED");
  }

  public static long insertSupplier(JdbcTemplate jdbcTemplate, String code) {
    return insertSupplier(jdbcTemplate, code, "供应商 " + code, "测试", "ENABLED");
  }

  public static long insertUser(
      JdbcTemplate jdbcTemplate,
      String username,
      String role,
      String department,
      String name) {
    jdbcTemplate.update(
        "INSERT INTO sys_user (username, password_hash, name, role, department, status, version)"
            + " VALUES (?, ?, ?, ?, ?, 'ENABLED', 0)",
        username,
        PASSWORD_HASH,
        name,
        role,
        department);
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    if (id == null) {
      throw new IllegalStateException("用户插入后取不到 id：" + username);
    }
    return id;
  }

  public static long insertUser(
      JdbcTemplate jdbcTemplate, String username, String role, String department) {
    return insertUser(jdbcTemplate, username, role, department, "测试用户 " + username);
  }

  public static long insertUser(JdbcTemplate jdbcTemplate, String username, String role) {
    return insertUser(jdbcTemplate, username, role, "QUALITY");
  }

  public static long insertUser(JdbcTemplate jdbcTemplate, String username) {
    return insertUser(jdbcTemplate, username, "MANAGER", "PURCHASE");
  }
}
