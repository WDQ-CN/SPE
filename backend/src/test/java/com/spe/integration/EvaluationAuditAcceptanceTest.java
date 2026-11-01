package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alibaba.excel.EasyExcel;
import com.spe.integration.support.TestFixtures;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * P3 的**留痕核对**（06 §2.8 / 13 §E20；形态照 P1 的 ` AuditTrailAcceptanceTest`）。
 *
 * <p>一次走遍 P3 的全部写路径（建/改/发布/改派/提交/导入 + 一次越权），逐条断言：
 * <ol>
 *   <li>该动作码**确实落了库**（行数 ≥ 1，不是"我以为会写"）；
 *   <li>target_type/target_id 指向正确的对象；
 *   <li>detail 里**没有口令/令牌**，也**没有别人评分单的分值**（13 §E20 的敏感面）；
 *   <li>越权留 `ACCESS_DENIED`。
 * </ol>
 */
class EvaluationAuditAcceptanceTest extends AbstractIntegrationTest {

  private static final AtomicInteger SEQ = new AtomicInteger(2151);
  private static final String PASSWORD = "Passw0rd!";

  @Test
  @DisplayName("P3 写路径全部留痕：TASK_CREATE/UPDATE/PUBLISH、SHEET_ASSIGN/SUBMIT/IMPORT + ACCESS_DENIED")
  void everyWritePathLeavesAnAuditRow() throws Exception {
    int year = SEQ.getAndIncrement();
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String manager = createUser(admin, "w5-audit-mgr-" + year, "MANAGER", "PURCHASE");
    String evaluator = createUser(admin, "w5-audit-eval-" + year, "EVALUATOR", "QUALITY");
    long evaluatorId = userIdOf("w5-audit-eval-" + year);
    long supplierId = insertSupplier("W5-AUDIT-" + year);
    long versionId = publishedDefaultVersionId();
    long templateId = publishedDefaultTemplateId();
    long approverId = userIdOf(ADMIN_USERNAME);

    // ① TASK_CREATE
    long taskId =
        dataAsLong(
            mockMvc
                .perform(
                    authed(
                        json(
                            post("/api/tasks"),
                            Map.of(
                                "name", "W5 留痕任务-" + year,
                                "year", year,
                                "quarter", 1,
                                "templateId", templateId,
                                "templateVersionId", versionId,
                                "deadline", LocalDate.of(year, 12, 31).toString(),
                                "supplierIds", List.of(supplierId),
                                "approverIds", List.of(approverId))),
                        manager))
                .andExpect(status().isOk())
                .andReturn());
    // ② TASK_UPDATE
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/tasks/" + taskId),
                    Map.of(
                        "name", "W5 留痕任务-" + year + "（改）",
                        "year", year,
                        "quarter", 1,
                        "templateId", templateId,
                        "templateVersionId", versionId,
                        "deadline", LocalDate.of(year, 12, 31).toString(),
                        "supplierIds", List.of(supplierId),
                        "approverIds", List.of(approverId),
                        "version", 0)),
                manager))
        .andExpect(status().isOk());
    // ③ TASK_PUBLISH
    mockMvc
        .perform(authed(json(post("/api/tasks/" + taskId + "/publish"), Map.of("version", 1)), manager))
        .andExpect(status().isOk());

    List<Long> sheetIds =
        jdbcTemplate.queryForList(
            "select s.id from sheet s join task_supplier ts on ts.id = s.task_supplier_id where ts.task_id = ?",
            Long.class,
            taskId);
    // ④ SHEET_ASSIGN（把四张都改派给同一个评估人）
    for (long sheetId : sheetIds) {
      mockMvc
          .perform(
              authed(
                  json(put("/api/sheets/" + sheetId + "/assign"),
                      Map.of("assigneeId", evaluatorId, "version", currentSheetVersion(sheetId))),
                  manager))
          .andExpect(status().isOk());
    }
    // ⑤ SHEET_SUBMIT：只提交质量维度（其它维度留给"未完整"的看板场景，不影响留痕断言）
    long qualitySheet = sheetIdOfDimension(taskId, "质量");
    List<Map<String, Object>> qualityScores = new ArrayList<>();
    qualityScores.add(Map.of("indicatorId", indicatorId("IQC_PASS_RATE"), "rawValue", "98.50"));
    qualityScores.add(Map.of("indicatorId", indicatorId("QUALITY_ACCIDENT"), "rawValue", "100"));
    qualityScores.add(Map.of("indicatorId", indicatorId("IQC_ON_TIME_RATE"), "rawValue", "96.00"));
    qualityScores.add(Map.of("indicatorId", indicatorId("QMS_AUDIT_SCORE"), "rawValue", "92"));
    mockMvc
        .perform(
            authed(json(put("/api/sheets/" + qualitySheet + "/scores"), Map.of("scores", qualityScores)), evaluator))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            authed(
                json(post("/api/sheets/" + qualitySheet + "/submit"),
                    Map.of("version", currentSheetVersion(qualitySheet))),
                evaluator))
        .andExpect(status().isOk());
    // ⑥ SHEET_IMPORT：走真实 multipart（行 = 供应商、列 = 指标编码）
    // ⚠️ 用 **evaluator** 而不是 manager：导入属 01 §5.5 FR-E 的评估人功能，且 04-096 起
    // 「导入」要求"参与该任务"（该任务四张单子都指派给了 evaluator，manager 一张都没有 ⇒ 403）。
    // 本表只有质量维度那一列（QMS_AUDIT_SCORE），而质量单刚在 ⑤ 提交过 ⇒ 会按"已提交"**跳过**，
    // 所以这里验的是**留痕**（SHEET_IMPORT 照样写），不是写入格数。
    mockMvc
        .perform(
            authed(
                multipart("/api/tasks/" + taskId + "/import")
                    .file(new MockMultipartFile("file", "import.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(taskId, "W5-AUDIT-" + year))),
                evaluator))
        .andExpect(status().isOk());
    // ⑦ ACCESS_DENIED：ADMIN 尝试发布（01 §7 只读）
    mockMvc
        .perform(authed(json(post("/api/tasks/" + taskId + "/publish"), Map.of("version", 2)), admin))
        .andExpect(status().isForbidden());

    // ---- 逐条核对留痕 ----
    assertThat(actionCount("TASK_CREATE", taskId)).as("TASK_CREATE 的 target 是任务")
        .isGreaterThanOrEqualTo(1L);
    assertThat(actionCount("TASK_UPDATE", taskId)).as("TASK_UPDATE").isGreaterThanOrEqualTo(1L);
    assertThat(actionCount("TASK_PUBLISH", taskId)).as("TASK_PUBLISH").isGreaterThanOrEqualTo(1L);
    assertThat(actionCount("SHEET_ASSIGN", taskId))
        .as("SHEET_ASSIGN 的 target 是**任务**（P2-9：原先写的是 TASK + 评估单 id，按任务查会串）；"
            + "四张评分单四次改派，评估单 id 在 detail 里")
        .isGreaterThanOrEqualTo(4L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'SHEET_ASSIGN'"
                    + " and target_type = 'TASK' and target_id = ? and detail like '%taskSupplierId%'",
                Long.class,
                taskId))
        .as("detail 必须带得回评估单 id，否则这条留痕定位不到改的是哪一张评估单")
        .isGreaterThanOrEqualTo(4L);
    assertThat(actionCount("SHEET_SUBMIT", taskSupplierIdOf(taskId)))
        .as("SHEET_SUBMIT 的 target 是评估单")
        .isGreaterThanOrEqualTo(1L);
    assertThat(actionCount("SHEET_IMPORT", taskId)).as("SHEET_IMPORT 的 target 是任务")
        .isGreaterThanOrEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'ACCESS_DENIED' and operator_id = ?",
                Long.class,
                userIdOf(ADMIN_USERNAME)))
        .as("越权留痕")
        .isGreaterThanOrEqualTo(1L);

    // detail 的敏感面：不出现口令/令牌，也不出现**别人评分单的分值**（本用例里 evaluator 的分值不该进 detail）
    List<String> details =
        jdbcTemplate.queryForList(
            "select detail from audit_log where target_type = 'TASK' and detail is not null", String.class);
    assertThat(details).as("先拿到正例：确实有 detail 可查").isNotEmpty();
    assertThat(details)
        .allSatisfy(
            detail -> {
              assertThat(detail).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("token");
              assertThat(detail).as("审计 detail 不记分值（13 §E20）").doesNotContain("98.50").doesNotContain("rawValue");
            });
  }

  // ---------- 工具 ----------

  private long actionCount(String action, long targetId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_log where action = ? and target_id = ?",
            Long.class,
            action,
            targetId);
    return count == null ? -1 : count;
  }

  private long taskSupplierIdOf(long taskId) {
    Long id =
        jdbcTemplate.queryForObject("select id from task_supplier where task_id = ? limit 1", Long.class, taskId);
    return id == null ? -1 : id;
  }

  private int currentSheetVersion(long sheetId) {
    Integer version = jdbcTemplate.queryForObject("select version from sheet where id = ?", Integer.class, sheetId);
    return version == null ? -1 : version;
  }

  private long sheetIdOfDimension(long taskId, String dimensionName) {
    Long id =
        jdbcTemplate.queryForObject(
            "select s.id from sheet s join task_supplier ts on ts.id = s.task_supplier_id"
                + " join dimension d on d.id = s.dimension_id where ts.task_id = ? and d.name = ?",
            Long.class,
            taskId,
            dimensionName);
    return id == null ? -1 : id;
  }

  private long indicatorId(String code) {
    Long id =
        jdbcTemplate.queryForObject("select id from indicator where code = ? order by id limit 1", Long.class, code);
    return id == null ? -1 : id;
  }

  private long publishedDefaultVersionId() {
    Long id =
        jdbcTemplate.queryForObject(
            "select v.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'",
            Long.class);
    return id == null ? -1 : id;
  }

  private long publishedDefaultTemplateId() {
    Long id =
        jdbcTemplate.queryForObject(
            "select t.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'",
            Long.class);
    return id == null ? -1 : id;
  }

  /** 造一张"只填质量维度几个格子"的 xlsx：导入本身要成功（留痕），内容不必完整（提交才要求完整）。 */
  private byte[] xlsx(long taskId, String supplierCode) {
    List<String> headers = new ArrayList<>();
    headers.add("供应商编码");
    headers.add("QMS_AUDIT_SCORE");
    List<List<Object>> rows = new ArrayList<>();
    rows.add(List.of(supplierCode, "88"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    EasyExcel.write(out).head(headers.stream().map(List::of).toList()).sheet("导入").doWrite(rows);
    assertThat(taskId).isPositive();
    return out.toByteArray();
  }

  private String createUser(String adminToken, String username, String role, String department)
      throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", username,
                        "password", PASSWORD,
                        "name", "用户 " + username,
                        "role", role,
                        "department", department)),
                adminToken))
        .andExpect(status().isOk());
    return login(username, PASSWORD);
  }

  private long insertSupplier(String code) { return TestFixtures.insertSupplier(jdbcTemplate, code, "供应商 " + code, "W5 留痕"); }

  private tools.jackson.databind.JsonNode data(
      org.springframework.test.web.servlet.MvcResult result) throws Exception {
    return objectMapper
        .readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
        .path("data");
  }

  private long dataAsLong(org.springframework.test.web.servlet.MvcResult result) throws Exception {
    return data(result).asLong();
  }
}
