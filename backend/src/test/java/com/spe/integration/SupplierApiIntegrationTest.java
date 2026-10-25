package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.Role;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商档案的端到端集成用例（10 §3 W3 的验收 A/B/C 组）。
 *
 * <p>覆盖单测（mock 掉 Mapper）验证不到的部分：真 SQL 的包含搜索与分页、`category IS NULL` 不被空条件
 * 过滤、守卫 UPDATE、编码唯一键、完整安全链（角色鉴权 + 越权留痕）、审计落库与 detail 口径。
 *
 * <p>本类标 `@Transactional` 换用例间隔离（容器共享）；涉及「是否真的提交」的断言在
 * {@link AuditRollbackIntegrationTest}（不加事务）。
 */
@Transactional
class SupplierApiIntegrationTest extends AbstractIntegrationTest {

  @Autowired private SupplierQueryFacade supplierQueryFacade;

  private long createSupplier(String token, String code, String name, String category) throws Exception {
    // 用 LinkedHashMap 而不是 Map.of：category 可能为 null，Map.of 不接受空值
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", name);
    body.put("category", category);
    mockMvc
        .perform(authed(json(post("/api/suppliers"), body), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    return supplierIdOf(code);
  }

  private long supplierIdOf(String code) {
    Long id = jdbcTemplate.queryForObject("SELECT id FROM supplier WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException("供应商不存在：" + code);
    }
    return id;
  }

  /** 建一个指定角色的真实账号并登录（集成层不该用自签令牌——那是黑盒 e2e 的手段，03 §14 R9-9）。 */
  private String loginAs(String adminToken, String username, Role role) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", username,
                        "password", DEFAULT_PASSWORD,
                        "name", "供应商用例账号",
                        "role", role.name(),
                        "department", "PURCHASE")),
                adminToken))
        .andExpect(status().isOk());
    return login(username, DEFAULT_PASSWORD);
  }

  private long countAudit(String action) {
    Long count =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Long.class, action);
    return count == null ? 0 : count;
  }

  @Test
  @DisplayName("新增 + 分页 + 搜索：total 是「满足条件」的总数，size 生效，按 code 排序")
  void createPaginateAndSearch() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    createSupplier(token, "W3T-A", "甲公司", "电子");
    createSupplier(token, "W3T-B", "乙公司", "电子");
    createSupplier(token, "W3T-C", "丙公司", "机械");

    mockMvc
        .perform(authed(get("/api/suppliers").param("page", "1").param("size", "2").param("keyword", "W3T-"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(2))
        .andExpect(jsonPath("$.data.total").value(3))
        .andExpect(jsonPath("$.data.list.length()").value(2))
        // ORDER BY code：分页稳定，第一页必是 A、B
        .andExpect(jsonPath("$.data.list[0].code").value("W3T-A"))
        .andExpect(jsonPath("$.data.list[1].code").value("W3T-B"))
        // 新建为 ENABLED、version=0
        .andExpect(jsonPath("$.data.list[0].status").value("ENABLED"))
        .andExpect(jsonPath("$.data.list[0].version").value(0));
  }

  @Test
  @DisplayName("搜索是包含匹配：用编码中段与名称中间字都能命中（前缀实现会失败）")
  void searchMatchesAnywhere() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    createSupplier(token, "W3T-MID-001", "华宇电子科技", "电子");

    mockMvc
        .perform(authed(get("/api/suppliers").param("keyword", "MID"), token))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].code").value("W3T-MID-001"));

    mockMvc
        .perform(authed(get("/api/suppliers").param("keyword", "电子科技"), token))
        .andExpect(jsonPath("$.data.total").value(1));
  }

  @Test
  @DisplayName("W6 LIKE 转义：% 与 _ 按字面匹配，不再是通配符（P2-37）")
  void keywordWildcardsAreEscaped() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    createSupplier(token, "W3T-LIT-001", "W6 100% 达成", "电子");
    createSupplier(token, "W3T-LIT-002", "W6 1000 达成", "电子");
    createSupplier(token, "W3T-LIT-003", "W6 A_B 公司", "电子");
    createSupplier(token, "W3T-LIT-004", "W6 AXB 公司", "电子");

    // 未转义时 % 会同时命中 "W6 1000 达成"
    mockMvc
        .perform(authed(get("/api/suppliers").param("keyword", "100% 达成"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].code").value("W3T-LIT-001"));

    // 未转义时 _ 会同时命中 "W6 AXB 公司"
    mockMvc
        .perform(authed(get("/api/suppliers").param("keyword", "A_B 公司"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].code").value("W3T-LIT-003"));
  }

  @Test
  @DisplayName("空 category 参数不过滤：category IS NULL 的行必须留在结果里（拼空通配会吃掉它）")
  void emptyCategoryParamKeepsNullCategoryRows() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    createSupplier(token, "W3T-NUL-A", "无分类公司", null);
    createSupplier(token, "W3T-NUL-B", "有分类公司", "电子");

    // category 传空串（前端未选择时的常见形态）：两个条件里 category 这段应整段跳过
    mockMvc
        .perform(authed(get("/api/suppliers").param("keyword", "W3T-NUL").param("category", ""), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2));

    // 完全不传 category 同样不过滤
    mockMvc
        .perform(authed(get("/api/suppliers").param("keyword", "W3T-NUL"), token))
        .andExpect(jsonPath("$.data.total").value(2));

    // 传具体分类则只命中带该分类的那家
    mockMvc
        .perform(authed(get("/api/suppliers").param("keyword", "W3T-NUL").param("category", "电子"), token))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].code").value("W3T-NUL-B"));
  }

  @Test
  @DisplayName("新增：分类可空，读回为 null（E10）")
  void categoryMayBeNull() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockMvc
        .perform(
            authed(
                json(post("/api/suppliers"), Map.of("code", "W3T-CAT", "name", "无分类公司")),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.category").value(nullValue()));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT category FROM supplier WHERE code = ?", String.class, "W3T-CAT"))
        .isNull();
  }

  @Test
  @DisplayName("新增：编码重复 → 400 + 2007（应用层前置校验；DB 兜底路径由单测覆盖）")
  void duplicateCodeRejected() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    createSupplier(token, "W3T-DUP", "甲公司", "电子");

    mockMvc
        .perform(
            authed(
                json(post("/api/suppliers"), Map.of("code", "W3T-DUP", "name", "乙公司", "category", "电子")),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2007));
  }

  @Test
  @DisplayName("编辑：陈旧 version → 409 + 3001；version 正确则生效且自增（守卫 UPDATE）")
  void editGuardsOnVersion() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long id = createSupplier(token, "W3T-EDIT", "原名", "电子");

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/suppliers/" + id),
                    Map.of("code", "W3T-EDIT", "name", "改名", "category", "电子", "version", 99)),
                token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3001));

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/suppliers/" + id),
                    Map.of("code", "W3T-EDIT", "name", "改名", "category", "电子", "version", 0)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("改名"))
        .andExpect(jsonPath("$.data.version").value(1));
  }

  @Test
  @DisplayName("编辑：把编码改成另一家已存在的编码 → 2007，不落库（E17 的查重）")
  void editRejectsCodeCollision() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    createSupplier(token, "W3T-E1", "甲公司", "电子");
    long second = createSupplier(token, "W3T-E2", "乙公司", "电子");

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/suppliers/" + second),
                    Map.of("code", "W3T-E1", "name", "乙公司", "category", "电子", "version", 0)),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2007));

    assertThat(
            jdbcTemplate.queryForObject("SELECT code FROM supplier WHERE id = ?", String.class, second))
        .isEqualTo("W3T-E2");
  }

  @Test
  @DisplayName("启停：守卫迁移生效并自增；重复提交同一迁移 → 3001；停用后行仍在且仍在列表里")
  void statusChangeKeepsRowAndGuards() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long id = createSupplier(token, "W3T-ST", "甲公司", "电子");

    mockMvc
        .perform(
            authed(json(put("/api/suppliers/" + id + "/status"), Map.of("status", "DISABLED", "version", 0)), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    assertThat(jdbcTemplate.queryForObject("SELECT status FROM supplier WHERE id = ?", String.class, id))
        .isEqualTo("DISABLED");
    assertThat(jdbcTemplate.queryForObject("SELECT version FROM supplier WHERE id = ?", Integer.class, id))
        .isEqualTo(1);

    // 重复提交（已是目标状态）→ 0 行 → 3001，而不是静默成功
    mockMvc
        .perform(
            authed(json(put("/api/suppliers/" + id + "/status"), Map.of("status", "DISABLED", "version", 1)), token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3001));

    // A8：停用只改状态——行还在，且列表（不按状态过滤）里仍能看到它
    mockMvc
        .perform(authed(get("/api/suppliers").param("keyword", "W3T-ST"), token))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].status").value("DISABLED"));
    assertThat(supplierQueryFacade.isEnabled(id)).isFalse();
  }

  @Test
  @DisplayName("越权：EVALUATOR / VIEWER → 403 + 4001 并留 ACCESS_DENIED；无 token → 401 + 1001")
  void nonAuthorizedRolesAreForbidden() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String evaluatorToken = loginAs(adminToken, "w3t_evaluator", Role.EVALUATOR);
    String viewerToken = loginAs(adminToken, "w3t_viewer", Role.VIEWER);
    long evaluatorId = userIdOf("w3t_evaluator");

    mockMvc
        .perform(authed(get("/api/suppliers"), evaluatorToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
    mockMvc
        .perform(authed(get("/api/suppliers"), viewerToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    // 越权留痕：两条请求各自留一条，分别对应两个操作人。
    // 不用 isIn(...) 做集合断言——那会让「只剩一条留痕」也通过，而少留一条正是要防的缺陷
    List<Long> deniedBy =
        jdbcTemplate.queryForList(
            "SELECT operator_id FROM audit_log WHERE action = 'ACCESS_DENIED'"
                + " AND operator_id IN (?, ?) ORDER BY id DESC",
            Long.class,
            evaluatorId,
            userIdOf("w3t_viewer"));
    assertThat(deniedBy).containsExactlyInAnyOrder(evaluatorId, userIdOf("w3t_viewer"));

    mockMvc
        .perform(get("/api/suppliers"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1001));
  }

  @Test
  @DisplayName("编辑：改码成功（新编码不冲突）→ 落库为新编码、version 自增，旧编码被释放")
  void editCanChangeCode() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long id = createSupplier(token, "W3T-RC-OLD", "改名用例", "电子");

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/suppliers/" + id),
                    Map.of("code", "W3T-RC-NEW", "name", "改名用例", "category", "电子", "version", 0)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.code").value("W3T-RC-NEW"))
        .andExpect(jsonPath("$.data.version").value(1));

    assertThat(jdbcTemplate.queryForObject("SELECT code FROM supplier WHERE id = ?", String.class, id))
        .isEqualTo("W3T-RC-NEW");
    // 旧编码已释放，可被再次使用
    createSupplier(token, "W3T-RC-OLD", "复用旧码", "电子");
  }

  @Test
  @DisplayName("查询参数上限与列长度对齐：keyword 超 100 字符 → 400 + 2001")
  void oversizedKeywordRejected() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockMvc
        .perform(authed(get("/api/suppliers").param("keyword", "x".repeat(101)), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
  }

  @Test
  @DisplayName("MANAGER 可写（01 §7 的两个授权角色都要验，不能只验 ADMIN）")
  void managerCanWrite() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String managerToken = loginAs(adminToken, "w3t_manager", Role.MANAGER);

    mockMvc
        .perform(
            authed(
                json(post("/api/suppliers"), Map.of("code", "W3T-MGR", "name", "经理建的", "category", "机械")),
                managerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  @DisplayName("审计三类动作落库，operator_id / target 正确，detail 只含业务字段")
  void auditPointsAreWritten() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long id = createSupplier(token, "W3T-AUDIT", "原名", "电子");
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/suppliers/" + id),
                    Map.of("code", "W3T-AUDIT", "name", "改名", "category", "机械", "version", 0)),
                token))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            authed(json(put("/api/suppliers/" + id + "/status"), Map.of("status", "DISABLED", "version", 1)), token))
        .andExpect(status().isOk());

    long operatorId = userIdOf(ADMIN_USERNAME);
    assertAuditRow("SUPPLIER_CREATE", id, operatorId);
    assertAuditRow("SUPPLIER_UPDATE", id, operatorId);
    assertAuditRow("SUPPLIER_STATUS_CHANGE", id, operatorId);

    Map<String, Object> latest =
        jdbcTemplate.queryForMap(
            "SELECT detail FROM audit_log WHERE action = 'SUPPLIER_STATUS_CHANGE' ORDER BY id DESC LIMIT 1");
    assertThat(String.valueOf(latest.get("detail"))).contains("DISABLED");
  }

  @Test
  @DisplayName("守卫失败不写审计：3001 之后该动作的审计行数不变（C2）")
  void guardFailureWritesNoAudit() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long id = createSupplier(token, "W3T-NOAUDIT", "甲公司", "电子");
    long before = countAudit("SUPPLIER_STATUS_CHANGE");

    mockMvc
        .perform(
            authed(json(put("/api/suppliers/" + id + "/status"), Map.of("status", "DISABLED", "version", 42)), token))
        .andExpect(status().isConflict());

    assertThat(countAudit("SUPPLIER_STATUS_CHANGE")).isEqualTo(before);
  }

  @Test
  @DisplayName("SupplierQueryFacade：findById / findByIds / isEnabled 走真实 SQL（供 P3 用）")
  void supplierQueryFacadeReadsRealRows() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long first = createSupplier(token, "W3T-FAC-1", "甲公司", "电子");
    long second = createSupplier(token, "W3T-FAC-2", "乙公司", null);

    assertThat(supplierQueryFacade.findById(first))
        .isPresent()
        .get()
        .satisfies(
            brief -> {
              assertThat(brief.code()).isEqualTo("W3T-FAC-1");
              assertThat(brief.category()).isEqualTo("电子");
              assertThat(brief.enabled()).isTrue();
            });
    assertThat(supplierQueryFacade.findByIds(List.of(first, second))).hasSize(2);
    assertThat(supplierQueryFacade.findByIds(List.of())).isEmpty();
    assertThat(supplierQueryFacade.isEnabled(999_999L)).isFalse();
  }

  private void assertAuditRow(String action, long targetId, long operatorId) {
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT operator_id, target_type, target_id FROM audit_log"
                + " WHERE action = ? AND target_id = ? ORDER BY id DESC LIMIT 1",
            action,
            targetId);
    assertThat(row.get("operator_id")).isEqualTo(operatorId);
    assertThat(row.get("target_type")).isEqualTo("SUPPLIER");
    assertThat(row.get("target_id")).isEqualTo(targetId);
  }
}
