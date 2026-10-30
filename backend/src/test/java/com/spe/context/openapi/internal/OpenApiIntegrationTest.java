package com.spe.context.openapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * M6 W2：开放 API 的读/写闭环（Token 鉴权、active 分页、指标快照、幂等 upsert、I2 守卫）。
 *
 * <p>夹具用 SQL 直接造「进行中任务 + 评估单 + 一张评分单」，只覆盖开放 API 的入口状态；
 * 发布/自动指派等语义已有 P3 用例。所有断言按本用例自己的 taskId/supplierCode 收敛。
 */
@Transactional
class OpenApiIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger(1);

  @Test
  @DisplayName("读：active 分页只含进行中任务；指标来自发布快照；Token 可访问")
  void activeTasksAndIndicators() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String token = createToken(admin, "M6-OPEN-READ");
    Fixture fixture = createFixture("OPEN-READ", "IN_PROGRESS", "SCORING", true);

    mockMvc
        .perform(authed(get("/open/v1/tasks/active?page=1&size=1"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.size").value(1))
        .andExpect(jsonPath("$.data.list.length()").value(1))
        .andExpect(jsonPath("$.data.list[0].suppliers[0].supplierCode").value(fixture.supplierCode()));

    mockMvc
        .perform(authed(get("/open/v1/tasks/" + fixture.taskId() + "/indicators"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.taskId").value((int) fixture.taskId()))
        .andExpect(jsonPath("$.data.dimensions.length()").value(4))
        .andExpect(jsonPath("$.data.dimensions[0].indicators[0].code").isString());

    mockMvc
        .perform(get("/open/v1/tasks/active"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(authed(get("/open/v1/tasks/active"), admin))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("写：幂等 upsert、source=API、值越界/供应商/指标错误明确")
  void upsertIsIdempotentAndValidates() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String token = createToken(admin, "M6-OPEN-WRITE");
    Fixture fixture = createFixture("OPEN-WRITE", "IN_PROGRESS", "SCORING", true);

    Map<String, Object> body =
        scoreBody(fixture.taskId(), fixture.supplierCode(), fixture.indicatorCode(), fixture.rawValue());
    mockMvc
        .perform(authed(json(put("/open/v1/scores"), body), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.source").value("API"))
        .andExpect(jsonPath("$.data.supplierCode").value(fixture.supplierCode()))
        .andExpect(jsonPath("$.data.indicatorCode").value(fixture.indicatorCode()))
        .andExpect(jsonPath("$.data.scoreValue").isNumber());

    // 同值重复上报：唯一键仍只有一行，source 保持 API
    mockMvc
        .perform(authed(json(put("/open/v1/scores"), body), token))
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM score WHERE sheet_id = ? AND indicator_id = ?",
                Long.class,
                fixture.sheetId(),
                fixture.indicatorId()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT source FROM score WHERE sheet_id = ? AND indicator_id = ?",
                String.class,
                fixture.sheetId(),
                fixture.indicatorId()))
        .isEqualTo("API");

    Map<String, Object> wrongSupplier =
        scoreBody(
            fixture.taskId(),
            "NO-SUCH-SUPPLIER-" + SEQ.getAndIncrement(),
            fixture.indicatorCode(),
            fixture.rawValue());
    mockMvc
        .perform(authed(json(put("/open/v1/scores"), wrongSupplier), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2037));

    Map<String, Object> wrongIndicator =
        scoreBody(fixture.taskId(), fixture.supplierCode(), "NO-SUCH-INDICATOR", fixture.rawValue());
    mockMvc
        .perform(authed(json(put("/open/v1/scores"), wrongIndicator), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2016));

    Map<String, Object> invalidValue =
        scoreBody(fixture.taskId(), fixture.supplierCode(), fixture.indicatorCode(), new BigDecimal("-1"));
    mockMvc
        .perform(authed(json(put("/open/v1/scores"), invalidValue), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2025));
  }

  @Test
  @DisplayName("I2 守卫：任务非进行中 2020、已提交评分单 2020；UNASSIGNED 可写且状态不变")
  void taskAndSheetGuards() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String token = createToken(admin, "M6-OPEN-GUARD");

    Fixture completed = createFixture("OPEN-COMPLETED", "COMPLETED", "SCORING", true);
    mockMvc
        .perform(
            authed(
                json(put("/open/v1/scores"), scoreBody(
                    completed.taskId(),
                    completed.supplierCode(),
                    completed.indicatorCode(),
                    completed.rawValue())),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2020));
    mockMvc
        .perform(
            authed(
                get("/open/v1/tasks/" + completed.taskId() + "/indicators"), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2020));

    Fixture submitted = createFixture("OPEN-SUBMITTED", "IN_PROGRESS", "SUBMITTED", true);
    mockMvc
        .perform(
            authed(
                json(put("/open/v1/scores"), scoreBody(
                    submitted.taskId(),
                    submitted.supplierCode(),
                    submitted.indicatorCode(),
                    submitted.rawValue())),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2020));

    Fixture unassigned = createFixture("OPEN-UNASSIGNED", "IN_PROGRESS", "UNASSIGNED", false);
    mockMvc
        .perform(
            authed(
                json(put("/open/v1/scores"), scoreBody(
                    unassigned.taskId(),
                    unassigned.supplierCode(),
                    unassigned.indicatorCode(),
                    unassigned.rawValue())),
                token))
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM sheet WHERE id = ?", String.class, unassigned.sheetId()))
        .as("API 写分不改评分单状态，仍待指派")
        .isEqualTo("UNASSIGNED");
  }

  private String createToken(String admin, String prefix) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                authed(
                    json(
                        post("/api/api-tokens"),
                        Map.of("name", prefix + "-" + SEQ.getAndIncrement())),
                    admin))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .path("data")
        .path("token")
        .asText();
  }

  private Fixture createFixture(
      String codePrefix, String taskStatus, String sheetStatus, boolean assigned) {
    int seq = SEQ.getAndIncrement();
    int year = 2080 + seq;
    String supplierCode = codePrefix + "-SUP-" + seq;
    jdbcTemplate.update(
        "INSERT INTO supplier (code, name, category, status, version)"
            + " VALUES (?, ?, 'M6 开放', 'ENABLED', 0)",
        supplierCode,
        "供应商 " + supplierCode);
    long supplierId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM supplier WHERE code = ?", Long.class, supplierCode);
    long versionId = defaultVersionId();
    Map<String, Object> indicator =
        jdbcTemplate.queryForMap(
            "SELECT i.id, i.code, i.type, i.score_mode, i.target_value, d.id AS dimension_id"
                + " FROM indicator i JOIN dimension d ON d.id = i.dimension_id"
                + " WHERE d.template_version_id = ? ORDER BY d.sort_no, i.sort_no, i.id LIMIT 1",
            versionId);
    long indicatorId = ((Number) indicator.get("id")).longValue();
    String indicatorCode = (String) indicator.get("code");
    long dimensionId = ((Number) indicator.get("dimension_id")).longValue();
    BigDecimal rawValue = rawValueFor(indicator);

    jdbcTemplate.update(
        "INSERT INTO eval_task (name, year, quarter, template_version_id, deadline, status, version)"
            + " VALUES (?, ?, 1, ?, ?, ?, 0)",
        "M6 开放任务 " + codePrefix + " " + seq,
        year,
        versionId,
        LocalDate.of(year, 3, 31),
        taskStatus);
    long taskId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM eval_task WHERE name = ?", Long.class,
            "M6 开放任务 " + codePrefix + " " + seq);
    jdbcTemplate.update(
        "INSERT INTO task_supplier (task_id, supplier_id, status, current_node, round, version)"
            + " VALUES (?, ?, 'SCORING', 0, 1, 0)",
        taskId,
        supplierId);
    long tsId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM task_supplier WHERE task_id = ? AND supplier_id = ?",
            Long.class,
            taskId,
            supplierId);

    Long assigneeId = null;
    if (assigned) {
      assigneeId = insertEvaluator("m6-open-eval-" + seq);
    }
    jdbcTemplate.update(
        "INSERT INTO sheet (task_supplier_id, dimension_id, assignee_id, status, version)"
            + " VALUES (?, ?, ?, ?, 0)",
        tsId,
        dimensionId,
        assigneeId,
        sheetStatus);
    long sheetId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM sheet WHERE task_supplier_id = ? AND dimension_id = ?",
            Long.class,
            tsId,
            dimensionId);
    return new Fixture(
        taskId, tsId, supplierId, supplierCode, indicatorId, indicatorCode, rawValue, sheetId);
  }

  private long insertEvaluator(String username) {
    jdbcTemplate.update(
        "INSERT INTO sys_user (username, password_hash, name, role, department, status, version)"
            + " VALUES (?, ?, ?, 'EVALUATOR', 'QUALITY', 'ENABLED', 0)",
        username,
        "$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm",
        "M6 开放评估人");
    return jdbcTemplate.queryForObject(
        "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
  }

  private long defaultVersionId() {
    return jdbcTemplate.queryForObject(
        "SELECT v.id FROM template_version v JOIN template t ON t.id = v.template_id"
            + " WHERE t.name = '季度绩效默认模板' AND v.version_no = 1 AND v.status = 'PUBLISHED'",
        Long.class);
  }

  private static BigDecimal rawValueFor(Map<String, Object> indicator) {
    if ("QUAL".equals(String.valueOf(indicator.get("type")))) {
      return new BigDecimal("4");
    }
    if ("TARGET".equals(String.valueOf(indicator.get("score_mode")))) {
      BigDecimal target = new BigDecimal(indicator.get("target_value").toString());
      return target.multiply(new BigDecimal("0.8"));
    }
    return new BigDecimal("80");
  }

  private static Map<String, Object> scoreBody(
      long taskId, String supplierCode, String indicatorCode, BigDecimal rawValue) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("taskId", taskId);
    body.put("supplierCode", supplierCode);
    body.put("indicatorCode", indicatorCode);
    body.put("rawValue", rawValue);
    return body;
  }

  private record Fixture(
      long taskId,
      long taskSupplierId,
      long supplierId,
      String supplierCode,
      long indicatorId,
      String indicatorCode,
      BigDecimal rawValue,
      long sheetId) {}
}
