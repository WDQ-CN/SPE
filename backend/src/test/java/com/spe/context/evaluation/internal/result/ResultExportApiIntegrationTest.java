package com.spe.context.evaluation.internal.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alibaba.excel.EasyExcel;
import com.spe.integration.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结果导出（M5 W5 / 01 R4）：xlsx 可读回、公式注入被前置单引号、空结果只有表头、中文文件名 RFC 5987、
 * 权限同结果列表。
 */
@Transactional
class ResultExportApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("导出：行数/维度列/公式注入防护/文件名编码")
  void exportReadsBackWithFormulaGuard() throws Exception {
    long taskId = seedResults();
    insertUser("w5-export-viewer", "VIEWER");
    String viewer = login("w5-export-viewer", "Admin@12345");

    MockHttpServletResponse response =
        mockMvc
            .perform(authed(get("/api/tasks/" + taskId + "/results/export"), viewer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    assertThat(response.getContentType())
        .contains("spreadsheetml.sheet");
    assertThat(response.getHeader("Content-Disposition"))
        .as("中文文件名必须按 RFC 5987 编码，实际=%s", response.getHeader("Content-Disposition"))
        .contains("filename*=UTF-8''");

    List<Map<Integer, String>> rows = readRows(response.getContentAsByteArray());
    assertThat(rows).as("两行有效结果").hasSize(2);
    assertThat(rows.get(0)).as("列数 = 4 + 4 个维度 × 2").hasSize(12);
    List<String> values = rows.stream().map(row -> row.get(0)).toList();
    assertThat(values).contains("'=1+1", "W5-OK");
    assertThat(rows.get(0).get(1)).as("恶意供应商名的单元格以单引号开头").startsWith("'=1+1");
  }

  @Test
  @DisplayName("空结果：只有表头（维度取自模板快照），没有任何数据行")
  void emptyResultsExportHeadersOnly() throws Exception {
    long taskId = insertTaskWithoutResults();
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    MockHttpServletResponse response =
        mockMvc
            .perform(authed(get("/api/tasks/" + taskId + "/results/export"), admin))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    List<Map<Integer, String>> rows = readRows(response.getContentAsByteArray());
    assertThat(rows).as("没有生效结果就没有数据行").isEmpty();
  }

  @Test
  @DisplayName("权限：EVALUATOR 403")
  void evaluatorIsForbidden() throws Exception {
    long taskId = seedResults();
    insertUser("w5-export-eval", "EVALUATOR");
    String evaluator = login("w5-export-eval", "Admin@12345");
    mockMvc
        .perform(authed(get("/api/tasks/" + taskId + "/results/export"), evaluator))
        .andExpect(status().isForbidden());
  }

  private List<Map<Integer, String>> readRows(byte[] bytes) {
    List<Object> raw =
        EasyExcel.read(new ByteArrayInputStream(bytes))
            .sheet(0)
            .headRowNumber(1)
            .doReadSync();
    @SuppressWarnings("unchecked")
    List<Map<Integer, String>> rows = (List<Map<Integer, String>>) (List<?>) raw;
    return rows;
  }

  private long seedResults() {
    long versionId = defaultVersionId();
    List<Long> dimensionIds =
        jdbcTemplate.queryForList(
            "SELECT id FROM dimension WHERE template_version_id = ? ORDER BY sort_no, id",
            Long.class,
            versionId);
    long taskId = insertTask("W5 导出任务");
    insertEffective(taskId, insertSupplier("=1+1", "=1+1 供应商"), dimensionIds, "80.00");
    insertEffective(taskId, insertSupplier("W5-OK", "正常供应商"), dimensionIds, "90.00");
    return taskId;
  }

  private long insertSupplier(String code, String name) {
    jdbcTemplate.update(
        "INSERT INTO supplier (code, name, category, status, version)"
            + " VALUES (?, ?, 'W5 导出', 'ENABLED', 0)",
        code,
        name);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM supplier WHERE code = ?", Long.class, code);
  }

  private void insertEffective(long taskId, long supplierId, List<Long> dimensionIds, String total) {
    jdbcTemplate.update(
        "INSERT INTO task_supplier (task_id, supplier_id, status, current_node, round,"
            + " total_score, total_grade, version) VALUES (?, ?, 'EFFECTIVE', 0, 1, ?, 'A', 0)",
        taskId,
        supplierId,
        new BigDecimal(total));
    Long tsId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM task_supplier WHERE task_id = ? AND supplier_id = ?",
            Long.class,
            taskId,
            supplierId);
    long index = 1;
    for (Long dimensionId : dimensionIds) {
      jdbcTemplate.update(
          "INSERT INTO task_supplier_dimension_result"
              + " (task_supplier_id, dimension_id, dimension_name, weight, score, grade)"
              + " VALUES (?, ?, ?, 50.00, ?, 'A')",
          tsId,
          dimensionId,
          "维度" + index,
          new BigDecimal(total));
      index++;
    }
  }

  private long insertTask(String name) {
    String taskName = name + " " + System.nanoTime();
    jdbcTemplate.update(
        "INSERT INTO eval_task (name, year, quarter, template_version_id, deadline, status, version)"
            + " VALUES (?, 2026, 2, ?, ?, 'COMPLETED', 0)",
        taskName,
        defaultVersionId(),
        LocalDate.of(2026, 12, 31));
    return jdbcTemplate.queryForObject(
        "SELECT id FROM eval_task WHERE name = ?", Long.class, taskName);
  }

  private long insertTaskWithoutResults() {
    return insertTask("W5 空任务");
  }

  private void insertUser(String username, String role) {
    jdbcTemplate.update(
        "INSERT INTO sys_user (username, password_hash, name, role, department, status, version)"
            + " VALUES (?, ?, ?, ?, 'QUALITY', 'ENABLED', 0)",
        username,
        "$2a$10$XN4FIyPaEhAv6Z/6Pms6quxg7qd1RlAZV8.dCcOl.YvcqCxxy9jsm",
        "W5 " + username,
        role);
  }

  private long defaultVersionId() {
    return jdbcTemplate.queryForObject(
        "SELECT v.id FROM template_version v JOIN template t ON t.id = v.template_id"
            + " WHERE t.name = '季度绩效默认模板' AND v.version_no = 1 AND v.status = 'PUBLISHED'",
        Long.class);
  }
}
