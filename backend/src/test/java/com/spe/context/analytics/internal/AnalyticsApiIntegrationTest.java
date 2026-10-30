package com.spe.context.analytics.internal;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.integration.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分析看板（M5 W4，01 B1/B2/B4/B5）：周期/供应商选项、趋势维度并集、排行环比（只认上一日历季度）、
 * 分类/等级过滤、等级分布、对比与权限。
 */
@Transactional
class AnalyticsApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("周期与供应商选项：含停用、含空分类；关键字按字面匹配")
  void periodsAndSupplierOptions() throws Exception {
    seedAllPeriods();
    String viewer = viewerToken();

    mockMvc
        .perform(authed(get("/api/analytics/periods"), viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].year").value(2026))
        .andExpect(jsonPath("$.data[0].quarter").value(2))
        .andExpect(jsonPath("$.data[1].quarter").value(1));

    mockMvc
        .perform(authed(get("/api/analytics/suppliers"), viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(3))
        .andExpect(jsonPath("$.data[2].code").value("W4-C"))
        .andExpect(jsonPath("$.data[2].category").value(nullValue()));

    mockMvc
        .perform(authed(get("/api/analytics/suppliers").param("keyword", "W4-A"), viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].code").value("W4-A"));
  }

  @Test
  @DisplayName("供应商选项：keyword 中的 % 与 _ 按字面匹配（不放大成全表）")
  void supplierKeywordEscapesWildcards() throws Exception {
    long percent = insertSupplier("W4-PCT", "折扣100%", "电子", "ENABLED");
    long percentOther = insertSupplier("W4-PCX", "折扣100X", "电子", "ENABLED");
    long underscore = insertSupplier("W4-LIT_A", "字面下划线", "电子", "ENABLED");
    long underscoreOther = insertSupplier("W4-LITXA", "通配下划线", "电子", "ENABLED");
    insertResult(2026, 2, percent, "80.00", "B");
    insertResult(2026, 2, percentOther, "80.00", "B");
    insertResult(2026, 2, underscore, "80.00", "B");
    insertResult(2026, 2, underscoreOther, "80.00", "B");

    String viewer = viewerToken();
    mockMvc
        .perform(authed(get("/api/analytics/suppliers").param("keyword", "100%"), viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].code").value("W4-PCT"));

    mockMvc
        .perform(authed(get("/api/analytics/suppliers").param("keyword", "LIT_A"), viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].code").value("W4-LIT_A"));
  }

  @Test
  @DisplayName("趋势：周期升序、维度名并集、缺口 null")
  void trendUnionsDimensions() throws Exception {
    seedAllPeriods();
    String viewer = viewerToken();

    mockMvc
        .perform(authed(get("/api/analytics/trend").param("supplierId", String.valueOf(supplierId("W4-A"))), viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.periods.length()").value(2))
        .andExpect(jsonPath("$.data.periods[0].year").value(2026))
        .andExpect(jsonPath("$.data.periods[0].quarter").value(1))
        .andExpect(jsonPath("$.data.periods[0].dimensions.length()").value(3))
        .andExpect(jsonPath("$.data.periods[0].dimensions[2].dimensionName").value("成本"))
        .andExpect(jsonPath("$.data.periods[0].dimensions[2].score").value(nullValue()))
        .andExpect(jsonPath("$.data.periods[1].dimensions.length()").value(3))
        .andExpect(jsonPath("$.data.periods[1].dimensions[2].dimensionName").value("成本"))
        .andExpect(jsonPath("$.data.periods[1].dimensions[2].score").value(80));
  }

  @Test
  @DisplayName("排行：总分降序、环比只认上一日历季度（无则 null）、分类/等级过滤")
  void rankingWithQuarterOverQuarter() throws Exception {
    seedAllPeriods();
    String viewer = viewerToken();

    mockMvc
        .perform(
            authed(
                get("/api/analytics/ranking").param("year", "2026").param("quarter", "2"),
                viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rows.length()").value(3))
        .andExpect(jsonPath("$.data.rows[0].supplierCode").value("W4-A"))
        .andExpect(jsonPath("$.data.rows[0].totalScore").value(80))
        .andExpect(jsonPath("$.data.rows[0].previousScore").value(70))
        .andExpect(jsonPath("$.data.rows[0].delta").value(10))
        .andExpect(jsonPath("$.data.rows[1].supplierCode").value("W4-C"))
        .andExpect(jsonPath("$.data.rows[1].previousScore").value(nullValue()))
        .andExpect(jsonPath("$.data.rows[1].delta").value(nullValue()))
        .andExpect(jsonPath("$.data.rows[2].supplierCode").value("W4-B"))
        .andExpect(jsonPath("$.data.rows[2].delta").value(-30));

    mockMvc
        .perform(
            authed(
                get("/api/analytics/ranking")
                    .param("year", "2026")
                    .param("quarter", "2")
                    .param("category", "电子"),
                viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rows.length()").value(2));

    mockMvc
        .perform(
            authed(
                get("/api/analytics/ranking")
                    .param("year", "2026")
                    .param("quarter", "2")
                    .param("grade", "b"),
                viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rows.length()").value(2));

    mockMvc
        .perform(
            authed(
                get("/api/analytics/ranking")
                    .param("year", "2026")
                    .param("quarter", "2")
                    .param("grade", "Z"),
                viewer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));

    // 空周期：空态而不是 500；等级分布返回固定的 A/B/C/D 四行 0
    mockMvc
        .perform(
            authed(
                get("/api/analytics/ranking").param("year", "2024").param("quarter", "1"),
                viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rows.length()").value(0));
    mockMvc
        .perform(
            authed(
                get("/api/analytics/grade-distribution").param("year", "2026").param("quarter", "2"),
                viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(4))
        .andExpect(jsonPath("$.data[1].grade").value("B"))
        .andExpect(jsonPath("$.data[1].count").value(2))
        .andExpect(jsonPath("$.data[3].count").value(1));
  }

  @Test
  @DisplayName("对比：保持请求顺序；无结果的 id → 400 点名；超过 10 个 → 400")
  void compareValidatesSuppliers() throws Exception {
    seedAllPeriods();
    String viewer = viewerToken();
    long a = supplierId("W4-A");
    long b = supplierId("W4-B");
    long c = supplierId("W4-C");

    mockMvc
        .perform(
            authed(
                get("/api/analytics/compare")
                    .param("year", "2026")
                    .param("quarter", "2")
                    .param("supplierIds", b + "," + a),
                viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rows[0].supplierCode").value("W4-B"))
        .andExpect(jsonPath("$.data.rows[1].supplierCode").value("W4-A"))
        .andExpect(jsonPath("$.data.rows[1].dimensions.length()").value(3));

    mockMvc
        .perform(
            authed(
                get("/api/analytics/compare")
                    .param("year", "2026")
                    .param("quarter", "1")
                    .param("supplierIds", a + "," + c),
                viewer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));

    mockMvc
        .perform(
            authed(
                get("/api/analytics/compare")
                    .param("year", "2026")
                    .param("quarter", "2")
                    .param("supplierIds", "1,2,3,4,5,6,7,8,9,10,11"),
                viewer))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("权限：VIEWER 可读，EVALUATOR 403")
  void onlyVisibleRolesCanRead() throws Exception {
    seedAllPeriods();
    insertUser("w4-analytics-eval", "EVALUATOR");
    String evaluator = login("w4-analytics-eval", "Admin@12345");
    mockMvc
        .perform(authed(get("/api/analytics/periods"), evaluator))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
  }

  private void seedAllPeriods() {
    long a = insertSupplier("W4-A", "供应商 A", "电子", "ENABLED");
    long b = insertSupplier("W4-B", "供应商 B", "电子", "ENABLED");
    long c = insertSupplier("W4-C", "供应商 C", null, "DISABLED");
    // 2026Q1：A=70（质量/交付），B=90（质量）
    long q1a = insertResult(2026, 1, a, "70.00", "C");
    insertSnapshot(q1a, 1, "质量", "70.00", "C");
    insertSnapshot(q1a, 2, "交付", "70.00", "C");
    long q1b = insertResult(2026, 1, b, "90.00", "A");
    insertSnapshot(q1b, 1, "质量", "90.00", "A");
    // 2026Q2：A=80（质量/交付/成本），B=60（质量），C=75（质量）
    long q2a = insertResult(2026, 2, a, "80.00", "B");
    insertSnapshot(q2a, 1, "质量", "80.00", "B");
    insertSnapshot(q2a, 2, "交付", "80.00", "B");
    insertSnapshot(q2a, 3, "成本", "80.00", "B");
    long q2b = insertResult(2026, 2, b, "60.00", "D");
    insertSnapshot(q2b, 1, "质量", "60.00", "D");
    long q2c = insertResult(2026, 2, c, "75.00", "B");
    insertSnapshot(q2c, 1, "质量", "75.00", "B");
  }

  private String viewerToken() throws Exception {
    insertUser("w4-analytics-viewer", "VIEWER");
    return login("w4-analytics-viewer", "Admin@12345");
  }

  private void insertUser(String username, String role) {
    jdbcTemplate.update(
        "INSERT INTO sys_user (username, password_hash, name, role, department, status, version)"
            + " VALUES (?, ?, ?, ?, 'QUALITY', 'ENABLED', 0)",
        username,
        "$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm",
        "W4 " + username,
        role);
  }

  private long insertSupplier(String code, String name, String category, String status) {
    jdbcTemplate.update(
        "INSERT INTO supplier (code, name, category, status, version) VALUES (?, ?, ?, ?, 0)",
        code,
        name,
        category,
        status);
    return supplierId(code);
  }

  private long supplierId(String code) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM supplier WHERE code = ?", Long.class, code);
  }

  private long insertResult(int year, int quarter, long supplierId, String total, String grade) {
    String name = "W4 分析 " + year + "Q" + quarter + " " + supplierId;
    jdbcTemplate.update(
        "INSERT INTO eval_task (name, year, quarter, template_version_id, deadline, status, version)"
            + " VALUES (?, ?, ?, ?, ?, 'COMPLETED', 0)",
        name,
        year,
        quarter,
        defaultVersionId(),
        LocalDate.of(year, 12, 31));
    Long taskId =
        jdbcTemplate.queryForObject("SELECT id FROM eval_task WHERE name = ?", Long.class, name);
    jdbcTemplate.update(
        "INSERT INTO task_supplier (task_id, supplier_id, status, current_node, round,"
            + " total_score, total_grade, version) VALUES (?, ?, 'EFFECTIVE', 0, 1, ?, ?, 0)",
        taskId,
        supplierId,
        new BigDecimal(total),
        grade);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM task_supplier WHERE task_id = ? AND supplier_id = ?",
        Long.class,
        taskId,
        supplierId);
  }

  private void insertSnapshot(
      long tsId, long dimensionId, String name, String score, String grade) {
    jdbcTemplate.update(
        "INSERT INTO task_supplier_dimension_result"
            + " (task_supplier_id, dimension_id, dimension_name, weight, score, grade)"
            + " VALUES (?, ?, ?, 25.00, ?, ?)",
        tsId,
        dimensionId,
        name,
        new BigDecimal(score),
        grade);
  }

  private long defaultVersionId() {
    return jdbcTemplate.queryForObject(
        "SELECT v.id FROM template_version v JOIN template t ON t.id = v.template_id"
            + " WHERE t.name = '季度绩效默认模板' AND v.version_no = 1 AND v.status = 'PUBLISHED'",
        Long.class);
  }
}
