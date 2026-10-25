package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.spe.common.page.PageQuery;
import com.spe.context.notify.internal.NotificationService;
import com.spe.context.notify.internal.NotificationSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * P4-W5：审批 / 结果 / 报告三类端点的 HTTP 契约与两层权限（02 §4、15 §E8/E15）。
 *
 * <p>夹具走真实 HTTP 建任务、发布，再用 SQL 把评分单补齐并推到待审批（T2 的提交语义由 P3 用例守着）。
 * 权限断言同时覆盖**角色入口**（4001）与**节点授权**（4003）两层——只测一层会漏掉 P0-1 那种"入口可见但
 * 业务不可达"的问题。
 */
class ApprovalApiIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger(2140);

  @Autowired private NotificationService notificationService;

  @Test
  @DisplayName("W5 通过端点：角色 4001 / 非本节点 4003 / 版本不符 3001，两级通过后结果与报告可读")
  void approveEndpointEnforcesRoleNodeAndVersionThenFinalizes() throws Exception {
    Fixture fixture = fixture();

    mockMvc
        .perform(
            authed(
                json(post("/api/task-suppliers/" + fixture.taskSupplierId() + "/approve"), Map.of("version", 0)),
                fixture.evaluatorToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    mockMvc
        .perform(
            authed(
                json(post("/api/task-suppliers/" + fixture.taskSupplierId() + "/approve"), Map.of("version", 0)),
                fixture.manager2Token()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4003));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/task-suppliers/" + fixture.taskSupplierId() + "/approve"),
                    Map.of("version", 99)),
                fixture.manager1Token()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3001));

    mockMvc
        .perform(
            authed(
                json(post("/api/task-suppliers/" + fixture.taskSupplierId() + "/approve"), Map.of("version", 0)),
                fixture.manager1Token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    mockMvc
        .perform(
            authed(
                json(post("/api/task-suppliers/" + fixture.taskSupplierId() + "/approve"), Map.of("version", 1)),
                fixture.manager2Token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    JsonNode rows =
        data(
                mockMvc
                    .perform(authed(get("/api/tasks/" + fixture.taskId() + "/results"), fixture.viewerToken()))
                    .andExpect(status().isOk())
                    .andReturn())
            .path("rows");
    assertThat(rows).as("只有一个已生效评估单").hasSize(1);
    assertThat(rows.get(0).path("totalScore").decimalValue()).isEqualByComparingTo("80.00");
    assertThat(rows.get(0).path("totalGrade").asText()).isEqualTo("B");
    assertThat(rows.get(0).path("dimensions")).as("结果列表含各维度分").hasSize(4);

    JsonNode resultTasks =
        data(
                mockMvc
                    .perform(authed(get("/api/results/tasks"), fixture.viewerToken()))
                    .andExpect(status().isOk())
                    .andReturn());
    assertThat(resultTasks.path("total").asLong()).as("结果入口对 VIEWER 可读").isGreaterThanOrEqualTo(1);
    assertThat(resultTasks.path("list").findValuesAsText("taskName"))
        .as("本轮任务必须出现在结果入口")
        .contains("P4 API 任务-" + fixture.year());

    // 过滤与 LIKE 转义（03 §41.3 的 P2-37）
    String seedName = "P4 API 任务-" + fixture.year();
    JsonNode completed =
        data(
                mockMvc
                    .perform(
                        authed(
                            get("/api/results/tasks")
                                .param("keyword", seedName)
                                .param("status", "COMPLETED"),
                            fixture.viewerToken()))
                    .andExpect(status().isOk())
                    .andReturn());
    assertThat(completed.path("total").asLong()).as("关键字 + 状态过滤").isEqualTo(1);

    JsonNode notCompleted =
        data(
                mockMvc
                    .perform(
                        authed(
                            get("/api/results/tasks")
                                .param("keyword", seedName)
                                .param("status", "IN_PROGRESS"),
                            fixture.viewerToken()))
                    .andExpect(status().isOk())
                    .andReturn());
    assertThat(notCompleted.path("total").asLong()).as("状态过滤必须生效").isZero();

    JsonNode literalWildcard =
        data(
                mockMvc
                    .perform(
                        authed(
                            get("/api/results/tasks").param("keyword", seedName.replace(' ', '_')),
                            fixture.viewerToken()))
                    .andExpect(status().isOk())
                    .andReturn());
    assertThat(literalWildcard.path("total").asLong())
        .as("_ 按字面匹配（未转义会命中空格）")
        .isZero();

    mockMvc
        .perform(authed(get("/api/results/tasks"), fixture.evaluatorToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    mockMvc
        .perform(authed(get("/api/tasks/" + fixture.taskId() + "/results"), fixture.evaluatorToken()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    JsonNode report =
        data(
            mockMvc
                .perform(
                    authed(
                        get("/api/task-suppliers/" + fixture.taskSupplierId() + "/report"),
                        fixture.evaluatorToken()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(report.path("scoreMode").asText()).isEqualTo("SNAPSHOT");
    assertThat(report.path("complete").asBoolean()).isTrue();
    assertThat(report.path("dimensions")).hasSize(4);
    assertThat(report.path("dimensions").get(0).path("indicators"))
        .as("指标明细必须带原始值/换算分/来源")
        .isNotEmpty();
    assertThat(report.path("dimensions").get(0).path("indicators").get(0).path("source").asText())
        .isEqualTo("MANUAL");
    assertThat(report.path("timeline")).as("两级审批记录各一条").hasSize(2);

    assertThat(auditCount("APPROVAL_APPROVE", fixture.taskSupplierId())).isEqualTo(2);
    assertThat(auditCount("TASK_SUPPLIER_FINALIZE", fixture.taskSupplierId())).isEqualTo(1);
  }

  @Test
  @DisplayName("W5 场景 3：第二级审批人是 ADMIN ⇒ 两级都可通过并定稿（ADMIN 在链内可审批）")
  void adminCanApproveAsSecondLevel() throws Exception {
    long adminId = userIdOf(ADMIN_USERNAME);
    Fixture fixture = fixture(adminId);

    mockMvc
        .perform(
            authed(
                json(post("/api/task-suppliers/" + fixture.taskSupplierId() + "/approve"), Map.of("version", 0)),
                fixture.manager1Token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    mockMvc
        .perform(
            authed(
                json(post("/api/task-suppliers/" + fixture.taskSupplierId() + "/approve"), Map.of("version", 1)),
                fixture.adminToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    Map<String, Object> row =
        jdbcTemplate.queryForMap("select * from task_supplier where id = ?", fixture.taskSupplierId());
    assertThat(row.get("status")).as("ADMIN 作为末级审批人也能完成定稿").isEqualTo("EFFECTIVE");
    assertThat(auditCount("APPROVAL_APPROVE", fixture.taskSupplierId())).isEqualTo(2);
  }

  @Test
  @DisplayName("W5 驳回端点：空意见 2029；合法驳回解锁评分单并通知评估人")
  void rejectEndpointReturns2029AndUnlocksSheets() throws Exception {
    Fixture fixture = fixture();

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/task-suppliers/" + fixture.taskSupplierId() + "/reject"),
                    Map.of("version", 0, "comment", "   ")),
                fixture.manager1Token()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2029));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/task-suppliers/" + fixture.taskSupplierId() + "/reject"),
                    Map.of("version", 0, "comment", "质量数据不达标")),
                fixture.manager1Token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));

    Map<String, Object> row =
        jdbcTemplate.queryForMap("select * from task_supplier where id = ?", fixture.taskSupplierId());
    assertThat(row.get("status")).isEqualTo("SCORING");
    assertThat(((Number) row.get("round")).intValue()).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from sheet where task_supplier_id = ? and version = 1",
                Long.class,
                fixture.taskSupplierId()))
        .as("4 张已提交评分单全部解锁")
        .isEqualTo(4);
    assertThat(
            notifications(
                    fixture.evaluatorId(), NotificationService.TYPE_TASK_SUPPLIER_REJECTED, "质量数据不达标")
                .size())
        .isEqualTo(1);

    JsonNode myApprovals =
        data(
                mockMvc
                    .perform(authed(get("/api/my/approvals"), fixture.manager1Token()))
                    .andExpect(status().isOk())
                    .andReturn())
            .path("list");
    assertThat(
            java.util.stream.StreamSupport.stream(myApprovals.spliterator(), false)
                .map(item -> item.path("taskSupplierId").asLong())
                .toList())
        .as("驳回后第一级待办清空")
        .doesNotContain(fixture.taskSupplierId());
  }

  @Test
  @DisplayName("W5 我的审批待办：按当前节点收敛，分页形状正确")
  void myApprovalsEndpointScopesByNode() throws Exception {
    Fixture fixture = fixture();

    JsonNode first =
        data(
            mockMvc
                .perform(authed(get("/api/my/approvals").param("page", "1").param("size", "10"), fixture.manager1Token()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(first.path("total").asLong()).isEqualTo(1);
    assertThat(first.path("page").asInt()).isEqualTo(1);
    assertThat(first.path("list")).hasSize(1);
    assertThat(first.path("list").get(0).path("taskSupplierId").asLong())
        .isEqualTo(fixture.taskSupplierId());
    assertThat(first.path("list").get(0).path("currentNode").asInt()).isEqualTo(1);

    JsonNode second =
        data(
            mockMvc
                .perform(authed(get("/api/my/approvals"), fixture.manager2Token()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(second.path("total").asLong()).as("第二级尚未轮到").isZero();

    JsonNode viewer =
        data(
            mockMvc
                .perform(authed(get("/api/my/approvals"), fixture.viewerToken()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(viewer.path("total").asLong()).as("VIEWER 没有审批节点").isZero();

    mockMvc.perform(get("/api/my/approvals")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("W5 报告可见性：未参与的评估人 4001，参与者读 TRIAL，报告不存在 2028")
  void reportEndpointLimitsEvaluatorToParticipation() throws Exception {
    Fixture fixture = fixture();
    String outsiderToken =
        createUserAndLogin(fixture.adminToken(), "p4-outsider-" + fixture.year(), "EVALUATOR", "DELIVERY");

    mockMvc
        .perform(authed(get("/api/task-suppliers/" + fixture.taskSupplierId() + "/report"), outsiderToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    JsonNode trial =
        data(
            mockMvc
                .perform(
                    authed(
                        get("/api/task-suppliers/" + fixture.taskSupplierId() + "/report"),
                        fixture.evaluatorToken()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(trial.path("scoreMode").asText()).as("未生效时降级为试算口径").isEqualTo("TRIAL");
    assertThat(trial.path("totalScore").decimalValue()).isEqualByComparingTo("80.00");
    assertThat(trial.path("timeline")).isEmpty();

    mockMvc
        .perform(authed(get("/api/task-suppliers/" + fixture.taskSupplierId() + "/report"), fixture.viewerToken()))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/task-suppliers/" + fixture.taskSupplierId() + "/report"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1001));
    mockMvc
        .perform(authed(get("/api/task-suppliers/99999999/report"), fixture.viewerToken()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2028));
  }

  // ---------- 夹具 ----------

  private record Fixture(
      int year,
      long taskId,
      long taskSupplierId,
      long evaluatorId,
      String adminToken,
      String manager1Token,
      String manager2Token,
      String evaluatorToken,
      String viewerToken) {}

  private Fixture fixture() throws Exception {
    return fixture(null);
  }

  /**
   * @param secondApproverId 第二级审批人；null = 用本用例新建的 MANAGER，非 null 时用指定用户（场景 3 用 ADMIN）
   */
  private Fixture fixture(Long secondApproverId) throws Exception {
    int year = SEQ.getAndIncrement();
    String adminToken = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String manager1Token = createUserAndLogin(adminToken, "p4-m1-" + year, "MANAGER", "OTHER");
    String manager2Token = createUserAndLogin(adminToken, "p4-m2-" + year, "MANAGER", "OTHER");
    String evaluatorToken = createUserAndLogin(adminToken, "p4-eval-" + year, "EVALUATOR", "QUALITY");
    String viewerToken = createUserAndLogin(adminToken, "p4-view-" + year, "VIEWER", "OTHER");
    long manager1 = userIdOf("p4-m1-" + year);
    long manager2 = userIdOf("p4-m2-" + year);
    long secondApprover = secondApproverId == null ? manager2 : secondApproverId;
    long evaluatorId = userIdOf("p4-eval-" + year);
    long supplierId = insertSupplier("P4-API-" + year);

    long taskId =
        dataAsLong(
            mockMvc
                .perform(
                    authed(
                        json(
                            post("/api/tasks"),
                            Map.of(
                                "name", "P4 API 任务-" + year,
                                "year", year,
                                "quarter", 1,
                                "templateId", publishedDefaultTemplateId(),
                                "templateVersionId", publishedDefaultVersionId(),
                                "deadline", LocalDate.of(year, 12, 31).toString(),
                                "supplierIds", List.of(supplierId),
                                "approverIds", List.of(manager1, secondApprover))),
                        manager1Token))
                .andExpect(status().isOk())
                .andReturn());
    mockMvc
        .perform(authed(json(post("/api/tasks/" + taskId + "/publish"), Map.of("version", 0)), manager1Token))
        .andExpect(status().isOk());

    Long taskSupplierId =
        jdbcTemplate.queryForObject(
            "select id from task_supplier where task_id = ?", Long.class, taskId);
    assertThat(taskSupplierId).isNotNull();
    fillScoresAndSubmit(taskSupplierId, evaluatorId);

    return new Fixture(
        year,
        taskId,
        taskSupplierId,
        evaluatorId,
        adminToken,
        manager1Token,
        manager2Token,
        evaluatorToken,
        viewerToken);
  }

  private String createUserAndLogin(String adminToken, String username, String role, String department)
      throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", username,
                        "password", DEFAULT_PASSWORD,
                        "name", "用户 " + username,
                        "role", role,
                        "department", department)),
                adminToken))
        .andExpect(status().isOk());
    return login(username, DEFAULT_PASSWORD);
  }

  /** 填满分数（每指标换算成 80）并把评分单置为已提交、评估单推到第一级待审。 */
  private void fillScoresAndSubmit(long taskSupplierId, long evaluatorId) {
    List<Long> sheetIds =
        jdbcTemplate.queryForList(
            "select id from sheet where task_supplier_id = ? order by id",
            Long.class,
            taskSupplierId);
    assertThat(sheetIds).isNotEmpty();
    for (Long sheetId : sheetIds) {
      List<Map<String, Object>> indicators =
          jdbcTemplate.queryForList(
              "select i.id, i.type, i.score_mode, i.target_value from indicator i"
                  + " join sheet s on s.dimension_id = i.dimension_id where s.id = ?",
              sheetId);
      for (Map<String, Object> indicator : indicators) {
        Object raw =
            "QUAL".equals(String.valueOf(indicator.get("type")))
                ? 4
                : "TARGET".equals(String.valueOf(indicator.get("score_mode")))
                    ? new BigDecimal(indicator.get("target_value").toString())
                        .multiply(new BigDecimal("0.8"))
                        .setScale(2, RoundingMode.HALF_UP)
                    : new BigDecimal("80");
        jdbcTemplate.update(
            "insert into score (sheet_id, indicator_id, raw_value, score_value, source)"
                + " values (?, ?, ?, 80.00, 'MANUAL')",
            sheetId,
            ((Number) indicator.get("id")).longValue(),
            raw);
      }
    }
    jdbcTemplate.update(
        "update sheet set assignee_id = ?, status = 'SUBMITTED' where task_supplier_id = ?",
        evaluatorId,
        taskSupplierId);
    jdbcTemplate.update(
        "update task_supplier set status = 'PENDING_APPROVAL', current_node = 1 where id = ?",
        taskSupplierId);
  }

  private long publishedDefaultVersionId() {
    Long id =
        jdbcTemplate.queryForObject(
            "select v.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'",
            Long.class);
    assertThat(id).isNotNull();
    return id;
  }

  private long publishedDefaultTemplateId() {
    Long id =
        jdbcTemplate.queryForObject(
            "select t.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'",
            Long.class);
    assertThat(id).isNotNull();
    return id;
  }

  private long insertSupplier(String code) {
    jdbcTemplate.update(
        "insert into supplier (code, name, category, status, version) values (?, ?, 'P4 API', 'ENABLED', 0)",
        code,
        "供应商 " + code);
    Long id = jdbcTemplate.queryForObject("select id from supplier where code = ?", Long.class, code);
    return id == null ? -1 : id;
  }

  private List<NotificationSummary> notifications(long receiverId, String type, String marker) {
    return notificationService.page(receiverId, PageQuery.of(1, 50)).list().stream()
        .filter(summary -> type.equals(summary.type()))
        .filter(summary -> summary.content() != null && summary.content().contains(marker))
        .toList();
  }

  private long auditCount(String action, long targetId) {
    Long value =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_log where action = ? and target_id = ?",
            Long.class,
            action,
            targetId);
    return value == null ? -1 : value;
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper
        .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .path("data");
  }

  private long dataAsLong(MvcResult result) throws Exception {
    return data(result).asLong();
  }
}
