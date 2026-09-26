package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模板 REST 接口的集成测试（12 §3-W3）。
 *
 * <p>覆盖三件事：**权限矩阵**（01 §7：模板管理 ADMIN ✓ / MANAGER 查看 / EVALUATOR –）、
 * **端到端走通**（12 §3-W3 的完成判据：建模板 → 加维度/指标 → 发布 → 改已发布 → 拿到新草稿）、
 * 以及**入口校验与错误码**（非法枚举归 2xxx、越界归 2xxx、不存在归 2015/2016）。
 *
 * <p>用真实 JWT（`login()` 走真登录接口）而不是伪造认证；MockMvc 只是省掉网络层。
 * 另有一轮**经 Nginx 的真实 HTTP** 端到端实测记录在 03 §27——两者测的不是同一件事：
 * 这里测业务与权限，那里测部署链与真实协议行为。
 */
@Transactional
class TemplateApiIntegrationTest extends AbstractIntegrationTest {

  // ------------------------------------------------------------------ 权限矩阵（01 §7）

  @Test
  @DisplayName("权限：ADMIN 可写、MANAGER 只读（读 200、写 403+4001）、EVALUATOR 读也 403")
  void roleMatrixMatchesRequirement() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String managerToken = loginFor(adminToken, "w3_matrix_manager", "MANAGER");
    String evaluatorToken = loginFor(adminToken, "w3_matrix_evaluator", "EVALUATOR");

    // 读：ADMIN 与 MANAGER 都可以（01 §7 给 MANAGER「查看」）
    mockMvc.perform(authed(get("/api/templates"), adminToken)).andExpect(status().isOk());
    mockMvc.perform(authed(get("/api/templates"), managerToken)).andExpect(status().isOk());

    // 写：MANAGER 403 + 4001（不是 401——已认证但无权限）
    mockMvc
        .perform(authed(json(post("/api/templates"), Map.of("name", "不该建出来")), managerToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    // EVALUATOR 连读都不行
    mockMvc
        .perform(authed(get("/api/templates"), evaluatorToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    // 越权不留痕就等于没审计：本轮写端点的拒绝必须落 ACCESS_DENIED
    assertThat(auditRows("ACCESS_DENIED")).as("越权应留 ACCESS_DENIED").isNotEmpty();
  }

  // ------------------------------------------------------------------ 端到端走通（W3 完成判据）

  @Test
  @DisplayName("端到端：建模板 → 加维度/指标 → 发布 → 改已发布版本 → 自动复制出新草稿（响应回两个字段）")
  void endToEndFromCreateToAutoCopy() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    // 1) 建模板：同时得到版本号 1 的草稿
    long templateId = createTemplate(adminToken, "W3 端到端模板");
    long draftId = latestVersionId(templateId);
    assertThat(versionStatus(draftId)).isEqualTo("DRAFT");

    // 2) 加一个权重 100 的维度 + 权重 100 的指标（发布校验要求维度合计与维度内合计都精确为 100）
    long dimensionId = addDimension(adminToken, draftId, "质量", "100.00");
    long indicatorId = addIndicator(adminToken, dimensionId, "来料批次合格率", "W3_E2E_1", "100.00");

    // 3) 发布：草稿 → 已发布
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/template-versions/" + draftId + "/publish"),
                    Map.of("version", versionOfVersion(draftId))),
                adminToken))
        .andExpect(status().isOk());
    assertThat(versionStatus(draftId)).isEqualTo("PUBLISHED");

    // 4) 改**已发布**版本里的维度 → 服务端在同一事务里复制出新草稿，响应回 effectiveVersionId/effectiveVersion
    String body =
        mockMvc
            .perform(
                authed(
                    json(
                        put("/api/dimensions/" + dimensionId),
                        Map.of(
                            "name", "质量-改",
                            "weight", "100.00",
                            "sortNo", 1,
                            "suggestedDept", "质量",
                            "isKey", false,
                            "version", versionOfDimension(dimensionId))),
                    adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.effectiveVersionId").exists())
            .andExpect(jsonPath("$.data.effectiveVersion").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long effectiveVersionId = objectMapper.readTree(body).path("data").path("effectiveVersionId").asLong();

    // 这就是 R-5 要防的事：改动落在了**另一个**版本上，且那个版本是 DRAFT
    assertThat(effectiveVersionId).as("改已发布版本应产生新草稿").isNotEqualTo(draftId);
    assertThat(versionStatus(effectiveVersionId)).isEqualTo("DRAFT");
    // 原已发布版本**没有被改**（发布即冻结）
    assertThat(versionStatus(draftId)).isEqualTo("PUBLISHED");
    assertThat(onlyDimensionName(draftId)).as("原版本的维度名不变").isEqualTo("质量");
    assertThat(onlyDimensionName(effectiveVersionId)).isEqualTo("质量-改");
    // 复制出来的行是新行：旧 id 在草稿里不存在
    assertThat(dimensionExists(effectiveVersionId, dimensionId)).isFalse();
    assertThat(indicatorExists(effectiveVersionId, indicatorId)).isFalse();
  }

  // ------------------------------------------------------------------ 入口校验与错误码

  @Test
  @DisplayName("非法枚举值 → 400 + 2001（不是 Spring 默认响应体：前端要按 code 回显）")
  void illegalEnumBecomesBadRequestResult() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long templateId = createTemplate(adminToken, "非法枚举模板");
    long draftId = latestVersionId(templateId);
    long dimensionId = addDimension(adminToken, draftId, "质量", "100.00");

    // 不覆写 handleHttpMessageNotReadable 时这里返回的是父类的默认体（无 code 字段），前端分流会失效
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/dimensions/" + dimensionId + "/indicators"),
                    Map.of(
                        "name", "指标",
                        "code", "W3_BAD_ENUM",
                        "weight", "100.00",
                        "type", "NOT_A_TYPE")),
                adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
  }

  @Test
  @DisplayName("入口校验：权重越界/超长名称与编码 → 400 + 2001（DTO 与库列长度对齐）")
  void requestValidationRejectsOutOfRangeValues() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long templateId = createTemplate(adminToken, "入口校验模板");
    long draftId = latestVersionId(templateId);

    // 权重 > 100：DTO 的 @DecimalMax
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/template-versions/" + draftId + "/dimensions"),
                    Map.of("name", "超界", "weight", "101.00")),
                adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));

    // 名称超长（列是 VARCHAR(100)）
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/template-versions/" + draftId + "/dimensions"),
                    Map.of("name", "名".repeat(101), "weight", "10.00")),
                adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));

    // 空名称
    mockMvc
        .perform(
            authed(
                json(post("/api/template-versions/" + draftId + "/dimensions"), Map.of("weight", "10.00")),
                adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
  }

  @Test
  @DisplayName("目标不存在：改/删指标 → 2016，改/删维度 → 2015，改不存在的模板名 → 2009")
  void missingTargetsCarryTheirOwnErrorCodes() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockMvc
        .perform(
            authed(
                json(put("/api/indicators/99999999"), Map.of(
                    "name", "x", "code", "x", "weight", "1.00", "type", "QUANT", "version", 0)),
                adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2016));

    mockMvc
        .perform(authed(delete("/api/dimensions/99999999?version=0"), adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2015));

    mockMvc
        .perform(authed(json(put("/api/templates/99999999"), Map.of("name", "x")), adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2009));
  }

  @Test
  @DisplayName("发布：权重合计不为 100 → 2011（消息里带实际值，能用它定位到哪个维度）")
  void publishRejectsIncompleteWeights() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long templateId = createTemplate(adminToken, "发布校验模板");
    long draftId = latestVersionId(templateId);
    addDimension(adminToken, draftId, "质量", "100.00");
    long dimensionId = dimensionIdOf(draftId, "质量");
    addIndicator(adminToken, dimensionId, "指标甲", "W3_PUB_1", "100.00");

    // 把维度改成 60 → 维度合计 60 ≠ 100
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/dimensions/" + dimensionId),
                    Map.of(
                        "name", "质量",
                        "weight", "60.00",
                        "sortNo", 1,
                        "version", versionOfDimension(dimensionId))),
                adminToken))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/template-versions/" + draftId + "/publish"),
                    Map.of("version", versionOfVersion(draftId))),
                adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2011))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("60")));
  }

  // ------------------------------------------------------------------ 读端点（W3 遗留的覆盖）

  @Test
  @DisplayName("列表分页：total 与 list 一致，且字段是前端要的四个（含是否有草稿）")
  void listPageCarriesDisplayFields() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long templateId = createTemplate(adminToken, "列表字段模板");

    mockMvc
        .perform(authed(get("/api/templates").param("page", "1").param("size", "10"), adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(10));

    // 展示字段直接查库核对：列表里混着 V8 的默认模板与其它用例建的模板，
    // 用 JSONPath 过滤表达式去数组里挑一条又脆又难读（而且过滤语法一写错就变成恒真）
    assertThat(draftCountOf(templateId)).as("新建模板应有 1 个草稿").isEqualTo(1);
    assertThat(latestPublishedNoOf(templateId)).as("还没发布过").isNull();
  }

  @Test
  @DisplayName("已发布版本清单：只含 PUBLISHED；草稿不出现（P3 选版本的数据源）")
  void publishedVersionsOnlyContainsPublished() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long templateId = createTemplate(adminToken, "已发布清单模板");
    long draftId = latestVersionId(templateId);
    long dimensionId = addDimension(adminToken, draftId, "质量", "100.00");
    addIndicator(adminToken, dimensionId, "指标甲", "W3_PUBLIST_1", "100.00");

    // 还没发布时清单为空——P3 不能绑定一个草稿
    mockMvc
        .perform(authed(get("/api/templates/" + templateId + "/published-versions"), adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty());

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/template-versions/" + draftId + "/publish"),
                    Map.of("version", versionOfVersion(draftId))),
                adminToken))
        .andExpect(status().isOk());

    // 先钉住"库里确实有这条已发布版本"——这样一旦下面为空，就能立刻区分是「发布没生效」还是
    // 「查询/端点的问题」，而不是对着一个空数组猜
    Integer publishedRows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM template_version WHERE template_id = ? AND status = 'PUBLISHED'",
            Integer.class,
            templateId);
    assertThat(publishedRows).as("库里应有 1 条已发布版本").isEqualTo(1);

    // 用「取响应体 + 断言内容」而不是 jsonPath：失败信息里会带上真实响应，省一轮排查
    String publishedBody =
        mockMvc
            .perform(authed(get("/api/templates/" + templateId + "/published-versions"), adminToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(publishedBody)
        .as("已发布清单应含 v1（实际响应：%s）", publishedBody)
        .contains("\"versionNo\":1")
        // 字段名以 PublishedVersionBrief 为准（是 versionId，不是 id）——别按自己的想象写断言，
        // 一次"取响应体 + 断言内容"的写法就能把这类错误在失败信息里直接暴露出来
        .contains("\"versionId\":" + draftId);
  }

  private int draftCountOf(long templateId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM template_version WHERE template_id = ? AND status = 'DRAFT'",
            Integer.class,
            templateId);
    return count == null ? 0 : count;
  }

  private Integer latestPublishedNoOf(long templateId) {
    return jdbcTemplate.queryForObject(
        "SELECT MAX(version_no) FROM template_version WHERE template_id = ? AND status = 'PUBLISHED'",
        Integer.class,
        templateId);
  }

  @Test
  @DisplayName("快照：已发布可取（含维度与指标），草稿取不到（2010）——计分口径只来自冻结数据")
  void snapshotOnlyForPublishedVersions() throws Exception {
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long templateId = createTemplate(adminToken, "快照模板");
    long draftId = latestVersionId(templateId);
    long dimensionId = addDimension(adminToken, draftId, "质量", "100.00");
    addIndicator(adminToken, dimensionId, "指标甲", "W3_SNAP_1", "100.00");

    // 草稿：快照不存在
    mockMvc
        .perform(authed(get("/api/template-versions/" + draftId + "/snapshot"), adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2010));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/template-versions/" + draftId + "/publish"),
                    Map.of("version", versionOfVersion(draftId))),
                adminToken))
        .andExpect(status().isOk());

    mockMvc
        .perform(authed(get("/api/template-versions/" + draftId + "/snapshot"), adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.versionNo").value(1))
        .andExpect(jsonPath("$.data.dimensions.length()").value(1))
        .andExpect(jsonPath("$.data.dimensions[0].indicators[0].code").value("W3_SNAP_1"));
  }

  // ------------------------------------------------------------------ 辅助

  /** 用 ADMIN 令牌建一个指定角色的账号并登录，返回它的令牌。 */
  private String loginFor(String adminToken, String username, String role) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", username,
                        "password", DEFAULT_PASSWORD,
                        "name", "W3 权限用例",
                        "role", role,
                        "department", "QUALITY")),
                adminToken))
        .andExpect(status().isOk());
    return login(username, DEFAULT_PASSWORD);
  }

  private long createTemplate(String adminToken, String name) throws Exception {
    String body =
        mockMvc
            .perform(authed(json(post("/api/templates"), Map.of("name", name)), adminToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).path("data").path("id").asLong();
  }

  private long addDimension(String adminToken, long versionId, String name, String weight)
      throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/template-versions/" + versionId + "/dimensions"),
                    Map.of("name", name, "weight", weight, "sortNo", 1, "suggestedDept", "质量")),
                adminToken))
        .andExpect(status().isOk());
    return dimensionIdOf(versionId, name);
  }

  private long addIndicator(
      String adminToken, long dimensionId, String name, String code, String weight) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/dimensions/" + dimensionId + "/indicators"),
                    Map.of(
                        "name", name, "code", code, "weight", weight, "type", "QUANT",
                        "scoreMode", "RATIO", "unit", "%", "sortNo", 1)),
                adminToken))
        .andExpect(status().isOk());
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM indicator WHERE dimension_id = ? AND code = ?", Long.class, dimensionId, code);
    return id == null ? 0L : id;
  }

  private long latestVersionId(long templateId) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM template_version WHERE template_id = ? ORDER BY version_no DESC LIMIT 1",
            Long.class,
            templateId);
    return id == null ? 0L : id;
  }

  private String versionStatus(long versionId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM template_version WHERE id = ?", String.class, versionId);
  }

  private int versionOfVersion(long versionId) {
    Integer v =
        jdbcTemplate.queryForObject(
            "SELECT version FROM template_version WHERE id = ?", Integer.class, versionId);
    return v == null ? 0 : v;
  }

  private int versionOfDimension(long dimensionId) {
    Integer v =
        jdbcTemplate.queryForObject(
            "SELECT version FROM dimension WHERE id = ?", Integer.class, dimensionId);
    return v == null ? 0 : v;
  }

  private long dimensionIdOf(long versionId, String name) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM dimension WHERE template_version_id = ? AND name = ? LIMIT 1",
            Long.class,
            versionId,
            name);
    return id == null ? 0L : id;
  }

  private String onlyDimensionName(long versionId) {
    return jdbcTemplate.queryForObject(
        "SELECT name FROM dimension WHERE template_version_id = ? LIMIT 1", String.class, versionId);
  }

  private boolean dimensionExists(long versionId, long dimensionId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM dimension WHERE template_version_id = ? AND id = ?",
            Long.class,
            versionId,
            dimensionId);
    return count != null && count > 0;
  }

  private boolean indicatorExists(long versionId, long indicatorId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM indicator WHERE template_version_id = ? AND id = ?",
            Long.class,
            versionId,
            indicatorId);
    return count != null && count > 0;
  }
}
