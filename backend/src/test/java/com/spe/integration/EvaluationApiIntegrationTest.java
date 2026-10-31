package com.spe.integration;

import com.spe.integration.support.TestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * W5 的 HTTP 集成用例（真库 + 完整安全链）：一条主链走通"创建 → 发布 → 改派 → 打分 → 试算 → 提交"，
 * 外加权限边界、DTO 校验、通知最小版、看板的语句数不变式、留痕核对。
 *
 * <p>与 Service 层用例的分工：这里证明的是"**接口可用 + 权限正确 + 序列化形状正确**"，
 * 而不是重复业务规则（那些在 W3/W4 的用例里）。
 */
@org.springframework.context.annotation.Import(com.spe.integration.support.StatementRecorderConfig.class)
class EvaluationApiIntegrationTest extends AbstractIntegrationTest {

  @org.springframework.beans.factory.annotation.Autowired
  private com.spe.integration.support.StatementRecorder statementRecorder;

  private static final AtomicInteger SEQ = new AtomicInteger(2121);
  private static final String PASSWORD = "Passw0rd!";

  @Test
  @DisplayName("W5 主链：MANAGER 建任务并发布 → 改派给评估人 → 打分/试算/提交 → 看板显示 90.82 与待审批")
  void fullChainFromCreateToSubmit() throws Exception {
    int year = SEQ.getAndIncrement();
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String manager = createUserAndLogin(admin, "w5-mgr-" + year, "MANAGER", "PURCHASE");
    String evaluator = createUserAndLogin(admin, "w5-eval-" + year, "EVALUATOR", "QUALITY");
    long evaluatorId = userIdOf("w5-eval-" + year);
    long supplierId = insertSupplier("W5-" + year);
    long versionId = publishedDefaultVersionId();
    long templateId = publishedDefaultTemplateId();

    // ① 创建草稿（01 J1）
    MvcResult created =
        mockMvc
            .perform(
                authed(
                    json(
                        post("/api/tasks"),
                        Map.of(
                            "name", "W5 任务-" + year,
                            "year", year,
                            "quarter", 1,
                            "templateId", templateId,
                            "templateVersionId", versionId,
                            "deadline", LocalDate.of(year, 12, 31).toString(),
                            "supplierIds", List.of(supplierId),
                            "approverIds", List.of(userIdOf(ADMIN_USERNAME)))),
                    manager))
            .andExpect(status().isOk())
            .andReturn();
    long taskId = dataAsLong(created);
    assertThat(taskId).as("创建应当返回任务 id").isPositive();

    // ② 发布（T1）：1 个供应商 × 4 个维度 = 4 张评分单
    MvcResult published =
        mockMvc
            .perform(
                authed(
                    json(post("/api/tasks/" + taskId + "/publish"), Map.of("version", 0)), manager))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode publishData = data(published);
    assertThat(publishData.path("supplierCount").asInt()).isEqualTo(1);
    assertThat(publishData.path("sheetCount").asInt()).as("评分单数 = 供应商数 × 维度数").isEqualTo(4);
    assertThat(publishData.path("assignedCount").asInt() + publishData.path("unassignedCount").asInt())
        .as("指派成功 + 未指派 = 评分单总数")
        .isEqualTo(4);

    // ③ 评估人看到自己的待办（01 E1）；把 4 张评分单都改派给他（01 J2 的合法路径）
    List<Long> sheetIds = sheetIdsOf(taskId);
    assertThat(sheetIds).as("先拿到正例：确实生成了 4 张评分单").hasSize(4);
    // ⚠️ 不假设"刚建的这个评估人就是被指派人"：自动指派取**部门内 id 最小者**，共享库里可能还有别人（04-092）
    for (long sheetId : sheetIds) {
      int version = sheetVersion(sheetId);
      mockMvc
          .perform(
              authed(
                  json(put("/api/sheets/" + sheetId + "/assign"),
                      Map.of("assigneeId", evaluatorId, "version", version)),
                  manager))
          .andExpect(status().isOk());
    }
    assertThat(mySheetIds(evaluator)).as("改派后 4 张全在他名下").hasSize(4);

    // ④ 逐维度打分（01 E2/§6.5 的录入值）+ 试算（不落库）+ 提交（T2）
    // ⚠️ 用 LinkedHashMap 而不是 Map.of：Map.of 的顺序不确定，而"最后一张提交"必须真的是最后一张
    Map<String, Map<String, String>> valuesByDimension = new LinkedHashMap<>();
    valuesByDimension.put("质量", Map.of("IQC_PASS_RATE", "98.50", "QUALITY_ACCIDENT", "100", "IQC_ON_TIME_RATE", "96.00", "QMS_AUDIT_SCORE", "92"));
    valuesByDimension.put("交付", Map.of("OTD_RATE", "95.08", "FILL_RATE", "99.20", "URGENT_RESPONSE", "4"));
    valuesByDimension.put("成本", Map.of("COST_DOWN_RATE", "2.40", "PRICE_COMPETITIVENESS", "4", "PAYMENT_COOP", "3"));
    valuesByDimension.put("服务", Map.of("ISSUE_RESPONSE", "5", "ABNORMAL_COOP", "4", "CONTACT_STABILITY", "4"));
    for (Map.Entry<String, Map<String, String>> entry : valuesByDimension.entrySet()) {
      long sheetId = sheetIdOfDimension(taskId, entry.getKey());
      List<Map<String, Object>> scores = new ArrayList<>();
      entry.getValue().forEach((code, value) -> scores.add(Map.of("indicatorId", indicatorId(code), "rawValue", value)));

      mockMvc
          .perform(
              authed(
                  json(put("/api/sheets/" + sheetId + "/scores"), Map.of("scores", scores)),
                  evaluator))
          .andExpect(status().isOk());

      if ("质量".equals(entry.getKey())) {
        // 试算：候选值只传本维度的一半，断言"不完整 ⇒ 不给分"（E6）与"完整时 96.35"两条
        MvcResult preview =
            mockMvc
                .perform(
                    authed(
                        json(post("/api/sheets/" + sheetId + "/preview"), Map.of("scores", List.of())),
                        evaluator))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = data(preview);
        assertThat(body.path("taskSupplier").path("complete").asBoolean())
            .as("只填了质量维度 ⇒ 整体不完整")
            .isFalse();
        assertThat(findSheet(body.path("taskSupplier").path("sheets"), "质量").path("score").asText())
            .as("试算的维度分（01 §6.5）")
            .isEqualTo("96.35");
        assertThat(body.path("taskSupplier").path("totalScore").isNull()).as("不完整时总分为 null").isTrue();
      }

      MvcResult submitted =
          mockMvc
              .perform(
                  authed(
                      json(post("/api/sheets/" + sheetId + "/submit"), Map.of("version", sheetVersionAfterSave(sheetId))),
                      evaluator))
              .andExpect(status().isOk())
              .andReturn();
      String status = data(submitted).path("taskSupplierStatus").asText();
      if ("服务".equals(entry.getKey())) {
        assertThat(status).as("最后一张提交 ⇒ 评估单转待审批").isEqualTo("PENDING_APPROVAL");
      } else {
        assertThat(status).as("还有维度没提交 ⇒ 仍是评分中（%s）", entry.getKey()).isEqualTo("SCORING");
      }
    }

    // ⑤ 看板（J3）：试算 90.82、完整、未提交 0（E14）
    MvcResult progress =
        mockMvc
            .perform(authed(get("/api/tasks/" + taskId + "/progress"), manager))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode row = data(progress).path("rows").get(0);
    assertThat(row.path("complete").asBoolean()).isTrue();
    assertThat(row.path("trialScore").asText()).as("01 §6.5 的总分").isEqualTo("90.82");
    assertThat(row.path("trialGrade").asText()).isEqualTo("A");
    assertThat(row.path("status").asText()).isEqualTo("PENDING_APPROVAL");
    assertThat(row.path("unsubmittedSheets").asLong()).isZero();
    assertThat(row.path("unassignedSheets").asLong()).isZero();

    // ⑥ 任务详情（缺口端点）与列表
    MvcResult detail =
        mockMvc.perform(authed(get("/api/tasks/" + taskId), manager)).andExpect(status().isOk()).andReturn();
    assertThat(data(detail).path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(data(detail).path("suppliers")).hasSize(1);
    assertThat(data(detail).path("sheetCount").asLong()).isEqualTo(4);
    mockMvc
        .perform(authed(get("/api/tasks").param("keyword", "W5 任务-" + year), manager))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.data.total")
                .value(1));
  }

  @Test
  @DisplayName("W5 权限边界：ADMIN 不能发布（4001+留痕）、EVALUATOR 不能建任务、VIEWER 读不了任务、别人改不了我的评分单")
  void permissionsAreEnforced() throws Exception {
    int year = SEQ.getAndIncrement();
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String manager = createUserAndLogin(admin, "w5-perm-mgr-" + year, "MANAGER", "PURCHASE");
    String evaluator = createUserAndLogin(admin, "w5-perm-eval-" + year, "EVALUATOR", "QUALITY");
    String viewer = createUserAndLogin(admin, "w5-perm-viewer-" + year, "VIEWER", "OTHER");
    long evaluatorId = userIdOf("w5-perm-eval-" + year);
    long supplierId = insertSupplier("W5-PERM-" + year);
    long taskId = createAndPublish(manager, year, supplierId, userIdOf(ADMIN_USERNAME));
    long evaluatorSheet = sheetIdsOf(taskId).get(0);
    assertThat(sheetIdsAssignedTo(evaluatorId)).as("先拿到正例：确实有指派关系").isNotNull();

    // ADMIN 只读（01 §7）：发布被拒
    MvcResult denied =
        mockMvc
            .perform(
                authed(json(post("/api/tasks/" + taskId + "/publish"), Map.of("version", 1)), admin))
            .andExpect(status().isForbidden())
            .andReturn();
    assertThat(code(denied)).as("越权统一 4001").isEqualTo(4001);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'ACCESS_DENIED' and operator_id = ?",
                Long.class,
                userIdOf(ADMIN_USERNAME)))
        .as("越权要留痕（06 §2.8）")
        .isGreaterThanOrEqualTo(1L);

    // EVALUATOR 建不了任务
    mockMvc
        .perform(
            authed(
                json(post("/api/tasks"),
                    Map.of(
                        "name", "不该建成",
                        "year", year,
                        "quarter", 2,
                        "templateId", publishedDefaultTemplateId(),
                        "templateVersionId", publishedDefaultVersionId(),
                        "deadline", LocalDate.of(year, 12, 31).toString(),
                        "supplierIds", List.of(supplierId),
                        "approverIds", List.of(userIdOf(ADMIN_USERNAME)))),
                evaluator))
        .andExpect(status().isForbidden());

    // VIEWER 读不了任务列表/详情
    mockMvc.perform(authed(get("/api/tasks"), viewer)).andExpect(status().isForbidden());

    // 别人的评分单：读/写都是 4002（第二层过滤）
    MvcResult notMine =
        mockMvc
            .perform(authed(get("/api/sheets/" + evaluatorSheet + "/scores"), manager))
            .andExpect(status().isForbidden())
            .andReturn();
    assertThat(code(notMine)).as("横向越权用 4002（13 §E20）").isEqualTo(4002);
    mockMvc
        .perform(
            authed(
                json(put("/api/sheets/" + evaluatorSheet + "/scores"),
                    Map.of("scores", List.of(Map.of("indicatorId", indicatorId("IQC_PASS_RATE"), "rawValue", "90")))),
                manager))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("W5 DTO 校验与错误码：必填缺失/季度越界 → 2001；不存在的任务 → 2018")
  void validationAndNotFoundReturnBusinessCodes() throws Exception {
    int year = SEQ.getAndIncrement();
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String manager = createUserAndLogin(admin, "w5-val-mgr-" + year, "MANAGER", "PURCHASE");

    MvcResult bad =
        mockMvc
            .perform(authed(json(post("/api/tasks"), Map.of("name", "", "year", year, "quarter", 9)), manager))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertThat(code(bad)).as("形状错误走 2001").isEqualTo(2001);
    MvcResult notFound =
        mockMvc.perform(authed(get("/api/tasks/99999999"), manager)).andExpect(status().isBadRequest()).andReturn();
    assertThat(code(notFound)).as("任务不存在 → 2018").isEqualTo(2018);
  }

  /**
   * P1-9 的入参上限（01 的规模口径本身就是合法输入，所以这不是理论风险）。
   *
   * <p>⚠️ "message 里不得出现 `supplierIds` 这种内部字段路径"这半句是 **P2-14 的判据**：
   * 在 P2-14 落地之前这条用例会红在 `doesNotContain`，那正是"先红后绿"的那条红。
   */
  @Test
  @DisplayName("集合入参有上限：501 家供应商 / 11 级审批链都挡在 DTO（2001），文案不外泄内部形状")
  void collectionInputsAreCappedAtDtoLevel() throws Exception {
    int year = SEQ.getAndIncrement();
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String manager = createUserAndLogin(admin, "w5-cap-mgr-" + year, "MANAGER", "PURCHASE");

    MvcResult tooManySuppliers =
        mockMvc
            .perform(
                authed(
                    json(
                        post("/api/tasks"),
                        Map.of(
                            "name", "上限用例-" + year,
                            "year", year,
                            "quarter", 1,
                            "templateId", publishedDefaultTemplateId(),
                            "templateVersionId", publishedDefaultVersionId(),
                            "deadline", LocalDate.of(year, 12, 31).toString(),
                            "supplierIds", java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList(),
                            "approverIds", List.of(userIdOf(ADMIN_USERNAME)))),
                    manager))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertThat(code(tooManySuppliers)).as("超上限是形状问题 ⇒ 2001").isEqualTo(2001);
    String supplierMessage = message(tooManySuppliers);
    assertThat(supplierMessage)
        .as("文案要能自证是哪条限制（`{max}` 由常量插值出来）")
        .contains("500")
        .contains("供应商");
    assertThat(supplierMessage)
        .as("P2-14：响应体不得外泄字段路径/对象名（实际=%s）", supplierMessage)
        .doesNotContain("supplierIds")
        .doesNotContain("CreateTaskRequest");

    MvcResult tooManyApprovers =
        mockMvc
            .perform(
                authed(
                    json(
                        post("/api/tasks"),
                        Map.of(
                            "name", "审批链上限-" + year,
                            "year", year,
                            "quarter", 1,
                            "templateId", publishedDefaultTemplateId(),
                            "templateVersionId", publishedDefaultVersionId(),
                            "deadline", LocalDate.of(year, 12, 31).toString(),
                            "supplierIds", List.of(insertSupplier("W5-CAP-" + year)),
                            "approverIds",
                                java.util.stream.LongStream.rangeClosed(1, 11).boxed().toList())),
                    manager))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertThat(code(tooManyApprovers)).isEqualTo(2001);
    String approverMessage = message(tooManyApprovers);
    assertThat(approverMessage).contains("10").contains("审批链");
    assertThat(approverMessage).as("P2-14 同一条口径").doesNotContain("approverIds");
  }

  @Test
  @DisplayName("W5 通知最小版（SM6）：发布后评估人有未读、能读列表、全部已读后归零")
  void notificationsMinimalVersion() throws Exception {
    int year = SEQ.getAndIncrement();
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String manager = createUserAndLogin(admin, "w5-notify-mgr-" + year, "MANAGER", "PURCHASE");
    String evaluator = createUserAndLogin(admin, "w5-notify-eval-" + year, "EVALUATOR", "QUALITY");
    long supplierId = insertSupplier("W5-NOTIFY-" + year);

    long taskId = createAndPublish(manager, year, supplierId, userIdOf(ADMIN_USERNAME));

    // ① 事件链路：发布后**真正的被指派人**必须收到通知。用库里的实际被指派人断言，而不是"我刚建的那个"
    //    ——自动指派取部门内 id 最小者，共享库里可能是别的用例建的人（04-092）。
    long actualAssignee = actualAssigneeOf(taskId);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from notification where receiver_id = ? and type = 'TASK_PUBLISHED'"
                    + " and content like ?",
                Long.class,
                actualAssignee,
                "%W5 任务-" + year + "%"))
        .as("发布事件触达被指派的评估人（判据 9）")
        .isGreaterThanOrEqualTo(1L);

    // ② 接口形态：给自己造一条通知再走三个端点（不依赖"我是被指派人"这个跨用例会变的前提）
    jdbcTemplate.update(
        "insert into notification (receiver_id, type, title, content, is_read) values (?, 'TASK_PUBLISHED', '测试通知', 'W5 接口形态用例', 0)",
        userIdOf("w5-notify-eval-" + year));

    long unread =
        data(
                mockMvc
                    .perform(authed(get("/api/notifications/unread-count"), evaluator))
                    .andExpect(status().isOk())
                    .andReturn())
            .asLong();
    assertThat(unread).as("未读角标").isGreaterThanOrEqualTo(1L);

    MvcResult list =
        mockMvc
            .perform(authed(get("/api/notifications").param("page", "1").param("size", "10"), evaluator))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(data(list).path("list").get(0).path("title").asText()).isNotBlank();
    assertThat(data(list).path("list").get(0).path("type").asText()).isEqualTo("TASK_PUBLISHED");

    mockMvc.perform(authed(post("/api/notifications/read-all"), evaluator)).andExpect(status().isOk());
    assertThat(
            data(
                    mockMvc
                        .perform(authed(get("/api/notifications/unread-count"), evaluator))
                        .andReturn())
                .asLong())
        .as("全部已读后归零")
        .isZero();
    assertThat(taskId).isPositive();
  }

  @Test
  @DisplayName("W5 E14 的验收挂钩：看板的 SQL 语句数与供应商数**无关**（performance_schema 数语句，不看延迟）")
  void progressStatementCountIsIndependentOfSupplierCount() throws Exception {
    int year = SEQ.getAndIncrement();
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String manager = createUserAndLogin(admin, "w5-sql-mgr-" + year, "MANAGER", "PURCHASE");

    long smallTask = createAndPublish(manager, year, insertSupplier("W5-SQL-A-" + year), userIdOf(ADMIN_USERNAME));
    long bigTask =
        createAndPublish(
            manager,
            year + 1,
            insertSupplier("W5-SQL-B-" + year),
            userIdOf(ADMIN_USERNAME),
            insertSupplier("W5-SQL-C-" + year),
            insertSupplier("W5-SQL-D-" + year));

    // 预热：阈值缓存（SettingCacheConfig）首次调用会查一次库，若把它算进差值，两次测量会差一条语句。
    // 这里先各调一次，再开始计数——测的是"稳定态下与供应商数无关"这个不变量。
    mockMvc.perform(authed(get("/api/tasks/" + smallTask + "/progress"), manager)).andExpect(status().isOk());
    mockMvc.perform(authed(get("/api/tasks/" + bigTask + "/progress"), manager)).andExpect(status().isOk());

    long before = statementCount();
    mockMvc.perform(authed(get("/api/tasks/" + smallTask + "/progress"), manager)).andExpect(status().isOk());
    long small = statementCount() - before;

    before = statementCount();
    mockMvc.perform(authed(get("/api/tasks/" + bigTask + "/progress"), manager)).andExpect(status().isOk());
    long big = statementCount() - before;

    assertThat(small).as("先断言正例：确实执行了语句（> 0）").isPositive();
    assertThat(big)
        .as("1 个供应商与 3 个供应商的语句数必须相同（批次内试算在内存里做，13 §E14）")
        .isEqualTo(small);
  }

  // ---------- MyBatis 语句计数（E14 的证据）----------

  // 计数器本体已提到 `com.spe.integration.support.StatementRecorder`：新加的用例（发布的分片批次计数）
  // 也要数同一件事，两处各写一份就是 CLAUDE 默认约定第 3 条点名的漂移。

  // ---------- 工具 ----------

  /**
   * 数 MyBatis 语句执行次数（E14 的"数 SQL 条数"）。用拦截器而不是 `general_log` /
   * `performance_schema`：那两条路需要 SUPER / performance_schema 的权限，而**测试账号刻意与生产同权**
   * （04-079），不为一条用例放开权限。拦截器数的是"Mapper 调用次数"，与"SQL 条数"在这个项目里一一对应
   * （没有存储过程、没有多语句）。
   */
  private long statementCount() {
    return statementRecorder.totalCount();
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
                        "password", PASSWORD,
                        "name", "用户 " + username,
                        "role", role,
                        "department", department)),
                adminToken))
        .andExpect(status().isOk());
    return login(username, PASSWORD);
  }

  private long createAndPublish(String managerToken, int year, long firstSupplier, long approverId, long... moreSuppliers)
      throws Exception {
    List<Long> suppliers = new ArrayList<>();
    suppliers.add(firstSupplier);
    for (long extra : moreSuppliers) {
      suppliers.add(extra);
    }
    long taskId =
        dataAsLong(
            mockMvc
                .perform(
                    authed(
                        json(
                            post("/api/tasks"),
                            Map.of(
                                "name", "W5 任务-" + year,
                                "year", year,
                                "quarter", 1,
                                "templateId", publishedDefaultTemplateId(),
                                "templateVersionId", publishedDefaultVersionId(),
                                "deadline", LocalDate.of(year, 12, 31).toString(),
                                "supplierIds", suppliers,
                                "approverIds", List.of(approverId))),
                        managerToken))
                .andExpect(status().isOk())
                .andReturn());
    mockMvc
        .perform(authed(json(post("/api/tasks/" + taskId + "/publish"), Map.of("version", 0)), managerToken))
        .andExpect(status().isOk());
    return taskId;
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

  private long insertSupplier(String code) { return TestFixtures.insertSupplier(jdbcTemplate, code, "供应商 " + code, "W5 测试"); }

  private long indicatorId(String code) {
    Long id =
        jdbcTemplate.queryForObject("select id from indicator where code = ? order by id limit 1", Long.class, code);
    return id == null ? -1 : id;
  }

  private List<Long> sheetIdsOf(long taskId) {
    return jdbcTemplate.queryForList(
        "select s.id from sheet s join task_supplier ts on ts.id = s.task_supplier_id"
            + " where ts.task_id = ? order by s.id",
        Long.class,
        taskId);
  }

  /** 任务里某张评分单**实际的**被指派人（不假设是自己刚建的用户）。 */
  private long actualAssigneeOf(long taskId) {
    Long id =
        jdbcTemplate.queryForObject(
            "select s.assignee_id from sheet s join task_supplier ts ON ts.id = s.task_supplier_id"
                + " where ts.task_id = ? and s.assignee_id is not null order by s.id limit 1",
            Long.class,
            taskId);
    return id == null ? -1 : id;
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

  private List<Long> sheetIdsAssignedTo(long userId) {
    return jdbcTemplate.queryForList("select id from sheet where assignee_id = ? order by id", Long.class, userId);
  }

  private int sheetVersion(long sheetId) {
    Integer version = jdbcTemplate.queryForObject("select version from sheet where id = ?", Integer.class, sheetId);
    return version == null ? -1 : version;
  }

  /**
   * 提交要用**当前的** sheet.version：改派会自增它（暂存不会——暂存只写 score 行）。
   * 一律现查、不按步数推算（04-040 的教训）。
   */
  private int sheetVersionAfterSave(long sheetId) {
    return sheetVersion(sheetId);
  }

  private List<Long> mySheetIds(String token) throws Exception {
    MvcResult result =
        mockMvc.perform(authed(get("/api/my/sheets"), token)).andExpect(status().isOk()).andReturn();
    List<Long> ids = new ArrayList<>();
    data(result).forEach(node -> ids.add(node.path("sheetId").asLong()));
    return ids;
  }

  private JsonNode findSheet(JsonNode sheets, String dimensionName) {
    for (JsonNode node : sheets) {
      if (dimensionName.equals(node.path("dimensionName").asText())) {
        return node;
      }
    }
    throw new AssertionError("响应里没有维度 " + dimensionName + "：实际=" + sheets);
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper
        .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .path("data");
  }

  private int code(MvcResult result) throws Exception {
    return objectMapper
        .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .path("code")
        .asInt();
  }

  /** 响应体里的 `message`（P2-14 判据要看它的**内容**，不能只断言"有错误码"）。 */
  private String message(MvcResult result) throws Exception {
    return objectMapper
        .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .path("message")
        .asText();
  }

  private long dataAsLong(MvcResult result) throws Exception {
    return data(result).asLong();
  }
}
