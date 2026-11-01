package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.Department;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserStatus;
import com.spe.context.system.internal.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * P2-8 管理员自锁守卫的 HTTP 与并发用例（`26` §3.4 的 I1~I4、C1~C2）。
 *
 * <p>「只有一个/恰好两个启用管理员」是这些用例的前提，而全量测试里别的类也会建 ADMIN
 * （如 `TaskDomainIntegrationTest` 的审批人）。因此本类用 SQL **暂时停用**除目标外的启用管理员，
 * 并在 {@link #restoreAdminState()} 里按记录精确恢复——只恢复"本类实际停用过"的 id，
 * 不碰本来就是 DISABLED 的账号。测试布置走 SQL 是有意的：守卫本身禁止通过这些操作清场。
 *
 * <p>并发用例直接调用 {@link UserService}（与 `ApprovalFlowIntegrationTest` 的并发用例同形），
 * 每次调用各占一个事务；HTTP 语义由 I1~I4 覆盖。
 */
@ResourceLock("user-admin-roster")
class UserAdminGuardIntegrationTest extends AbstractIntegrationTest {

  private static final String PREFIX = "p28-";

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired private UserService userService;

  /** 本类为布置场景而停用的管理员 id；`@AfterEach` 只恢复这些，避免误开别人刻意停用的账号。 */
  private final List<Long> temporarilyDisabled = new ArrayList<>();

  @AfterEach
  void restoreAdminState() {
    for (Long id : temporarilyDisabled) {
      jdbcTemplate.update(
          "UPDATE sys_user SET status = 'ENABLED', version = version + 1"
              + " WHERE id = ? AND status = 'DISABLED'",
          id);
    }
    temporarilyDisabled.clear();
    // 本类创建的账号直接删除：只停用会在共享 Testcontainers 库里逐轮累积（03 §50 P3-7）。
    // 审计行保留（operator_name 已落库），且 sys_user 没有外键引用，删除是安全的。
    jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE ?", PREFIX + "%");
  }

  @Test
  @DisplayName("P2-8 I1：ADMIN 停用自己 → 409 + 3002，库状态不变、不写审计")
  void selfDisableIsRejectedOverHttp() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long adminId = userIdOf(ADMIN_USERNAME);
    Map<String, Object> before = userRow(ADMIN_USERNAME);
    long auditsBefore = auditCount("USER_STATUS_CHANGE", adminId);

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/users/" + adminId + "/status"),
                    Map.of("status", "DISABLED", "version", before.get("version"))),
                token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3002));

    assertThat(userRow(ADMIN_USERNAME).get("status")).isEqualTo("ENABLED");
    assertThat(auditCount("USER_STATUS_CHANGE", adminId))
        .as("守卫拒绝发生在审计之前")
        .isEqualTo(auditsBefore);
  }

  @Test
  @DisplayName("P2-8 I2：最后一个管理员给自己降权 → 409 + 3003，角色不变")
  void demotingLastAdminIsRejectedOverHttp() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long adminId = userIdOf(ADMIN_USERNAME);
    disableOtherEnabledAdmins(adminId);

    Map<String, Object> row = userRow(ADMIN_USERNAME);
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/users/" + adminId),
                    Map.of(
                        "name", row.get("name"),
                        "role", "MANAGER",
                        "department", row.get("department"),
                        "version", row.get("version"))),
                token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3003));

    assertThat(userRow(ADMIN_USERNAME).get("role")).isEqualTo("ADMIN");
  }

  @Test
  @DisplayName("P2-8 I3：还有另一位启用管理员时，停用管理员放行并留审计")
  void disablingAdminWithAnotherAdminRemainsIsAllowed() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long a = createUser(token, unique("a"), Role.ADMIN);
    long b = createUser(token, unique("b"), Role.ADMIN);
    // A 停用 B：A 自己仍是管理员，系统不会失去最后一个 ADMIN
    String aName = (String) userRowById(a).get("username");
    String aToken = login(aName, DEFAULT_PASSWORD);

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/users/" + b + "/status"),
                    Map.of("status", "DISABLED", "version", 0)),
                aToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    assertThat(userRowById(b).get("status")).isEqualTo("DISABLED");
    assertThat(auditCount("USER_STATUS_CHANGE", b)).isEqualTo(1);
  }

  @Test
  @DisplayName("P2-8 I4：停用非管理员不受守卫影响")
  void disablingNonAdminIsAllowed() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long evaluator = createUser(token, unique("eval"), Role.EVALUATOR);

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/users/" + evaluator + "/status"),
                    Map.of("status", "DISABLED", "version", 0)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    assertThat(userRowById(evaluator).get("status")).isEqualTo("DISABLED");
  }

  @Test
  @DisplayName("P2-8 I5：还有另一位启用管理员时，管理员给自己降权放行")
  void selfDemotionWithAnotherAdminRemainsIsAllowed() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long a = createUser(token, unique("self"), Role.ADMIN);
    createUser(token, unique("peer"), Role.ADMIN);
    Map<String, Object> row = userRowById(a);
    String aToken = login((String) row.get("username"), DEFAULT_PASSWORD);

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/users/" + a),
                    Map.of(
                        "name", row.get("name"),
                        "role", "MANAGER",
                        "department", row.get("department"),
                        "version", row.get("version"))),
                aToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    assertThat(userRowById(a).get("role")).isEqualTo("MANAGER");
  }

  @Test
  @DisplayName("P2-8 C1：两个管理员互停 → 恰一人成功，另一个 3003，库里仍剩一个 ADMIN")
  void concurrentMutualDisableLeavesOneAdmin() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long a = createUser(token, unique("race-a"), Role.ADMIN);
    long b = createUser(token, unique("race-b"), Role.ADMIN);
    disableOtherEnabledAdmins(a, b);

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<ErrorCode> failures = new ArrayList<>();
    try {
      List<Future<ErrorCode>> futures = new ArrayList<>();
      futures.add(
          pool.submit(
              () -> {
                barrier.await();
                return tryChangeStatus(b, 0, a);
              }));
      futures.add(
          pool.submit(
              () -> {
                barrier.await();
                return tryChangeStatus(a, 0, b);
              }));
      for (Future<ErrorCode> future : futures) {
        failures.add(future.get(30, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(failures.stream().filter(Objects::isNull))
        .as("互停只能成功一个")
        .hasSize(1);
    assertThat(failures.stream().filter(Objects::nonNull))
        .as("后到者看到只剩一个管理员 ⇒ 3003（不是 5xxx）")
        .containsExactly(ErrorCode.LAST_ENABLED_ADMIN);
    assertThat(enabledAdminCount()).as("不变量：至少保留一个 ENABLED ADMIN").isEqualTo(1);
  }

  @Test
  @DisplayName("P2-8 C2：两个管理员互相降权 → 恰一人成功，另一个 3003，库里仍剩一个 ADMIN")
  void concurrentMutualDemotionLeavesOneAdmin() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long a = createUser(token, unique("demote-a"), Role.ADMIN);
    long b = createUser(token, unique("demote-b"), Role.ADMIN);
    disableOtherEnabledAdmins(a, b);
    Map<String, Object> rowA = userRowById(a);
    Map<String, Object> rowB = userRowById(b);

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<ErrorCode> failures = new ArrayList<>();
    try {
      List<Future<ErrorCode>> futures = new ArrayList<>();
      futures.add(
          pool.submit(
              () -> {
                barrier.await();
                return tryDemote(b, rowB);
              }));
      futures.add(
          pool.submit(
              () -> {
                barrier.await();
                return tryDemote(a, rowA);
              }));
      for (Future<ErrorCode> future : futures) {
        failures.add(future.get(30, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(failures.stream().filter(Objects::isNull)).as("互相降权只能成功一个").hasSize(1);
    assertThat(failures.stream().filter(Objects::nonNull))
        .as("后到者看到只剩一个管理员 ⇒ 3003")
        .containsExactly(ErrorCode.LAST_ENABLED_ADMIN);
    assertThat(enabledAdminCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("P2-8 C3：A 降 B 与 B 停 A 并发 → 恰一人成功，另一个 3003，库里仍剩一个 ADMIN")
  void concurrentDemoteAndDisableLeavesOneAdmin() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long a = createUser(token, unique("mix-a"), Role.ADMIN);
    long b = createUser(token, unique("mix-b"), Role.ADMIN);
    disableOtherEnabledAdmins(a, b);
    Map<String, Object> rowB = userRowById(b);

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<ErrorCode> failures = new ArrayList<>();
    try {
      List<Future<ErrorCode>> futures = new ArrayList<>();
      futures.add(
          pool.submit(
              () -> {
                barrier.await();
                return tryDemote(b, rowB);
              }));
      futures.add(
          pool.submit(
              () -> {
                barrier.await();
                return tryChangeStatus(a, 0, b);
              }));
      for (Future<ErrorCode> future : futures) {
        failures.add(future.get(30, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(failures.stream().filter(Objects::isNull))
        .as("降权与停用只能成功一个")
        .hasSize(1);
    assertThat(failures.stream().filter(Objects::nonNull))
        .as("后到者看到只剩一个管理员 ⇒ 3003")
        .containsExactly(ErrorCode.LAST_ENABLED_ADMIN);
    assertThat(enabledAdminCount()).isEqualTo(1);
  }

  // ---------- 夹具 ----------

  private ErrorCode tryChangeStatus(long targetId, int version, long operatorId) {
    try {
      userService.changeStatus(targetId, UserStatus.DISABLED, version, operatorId);
      return null;
    } catch (BizException ex) {
      return ex.errorCode();
    }
  }

  private ErrorCode tryDemote(long id, Map<String, Object> row) {
    try {
      userService.update(
          id,
          (String) row.get("name"),
          Role.MANAGER,
          Department.valueOf((String) row.get("department")),
          ((Number) row.get("version")).intValue());
      return null;
    } catch (BizException ex) {
      return ex.errorCode();
    }
  }

  /** 除 keep 以外，把当前所有启用中的 ADMIN 暂时停用（测试布置；恢复见 {@link #restoreAdminState()}）。 */
  private void disableOtherEnabledAdmins(long... keep) {
    StringBuilder sql =
        new StringBuilder("SELECT id FROM sys_user WHERE role = 'ADMIN' AND status = 'ENABLED'");
    if (keep.length > 0) {
      sql.append(" AND id NOT IN (");
      sql.append(String.join(",", java.util.Collections.nCopies(keep.length, "?")));
      sql.append(")");
    }
    Object[] args = new Object[keep.length];
    for (int i = 0; i < keep.length; i++) {
      args[i] = keep[i];
    }
    List<Long> ids = jdbcTemplate.queryForList(sql.toString(), Long.class, args);
    // 先记录再改：中途异常时 @AfterEach 仍能恢复已经改过的行
    temporarilyDisabled.addAll(ids);
    for (Long id : ids) {
      jdbcTemplate.update(
          "UPDATE sys_user SET status = 'DISABLED', version = version + 1"
              + " WHERE id = ? AND status = 'ENABLED'",
          id);
    }
  }

  private long createUser(String adminToken, String username, Role role) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", username,
                        "password", DEFAULT_PASSWORD,
                        "name", "P2-8 测试用户",
                        "role", role.name(),
                        "department", "OTHER")),
                adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    return userIdOf(username);
  }

  private String unique(String label) {
    return PREFIX + label + "-" + SEQ.incrementAndGet();
  }

  private Map<String, Object> userRow(String username) {
    return jdbcTemplate.queryForMap(
        "SELECT id, username, name, role, department, status, version FROM sys_user WHERE username = ?",
        username);
  }

  private Map<String, Object> userRowById(long id) {
    return jdbcTemplate.queryForMap(
        "SELECT id, username, name, role, department, status, version FROM sys_user WHERE id = ?",
        id);
  }

  private long auditCount(String action, long targetId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = ? AND target_id = ?",
            Long.class,
            action,
            targetId);
    return count == null ? 0L : count;
  }

  private long enabledAdminCount() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_user WHERE role = 'ADMIN' AND status = 'ENABLED'",
            Long.class);
    return count == null ? 0L : count;
  }
}
