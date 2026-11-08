package com.spe.context.reporting.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 报表中心（docs/erp/39 §1）：查询键为代码内固定 SQL，不接受用户输入 SQL。
 * 每次运行保存 JSON 快照，CSV 从快照导出，保证同一运行结果可重复下载。
 */
@Service
@RequiredArgsConstructor
public class ReportService {

  private static final Map<String, String> QUERIES =
      Map.of(
          "INVENTORY_STOCK",
          "SELECT warehouse_id AS warehouseId, item_id AS itemId,"
              + " SUM(qty_on_hand) AS qtyOnHand, SUM(total_value) AS totalValue"
              + " FROM inventory_stock WHERE company_id = ?"
              + " GROUP BY warehouse_id, item_id ORDER BY warehouse_id, item_id",
          "AR_AGING",
          "SELECT invoice_no AS invoiceNo, customer_id AS customerId, due_date AS dueDate,"
              + " total_amount - paid_amount AS outstanding"
              + " FROM sales_invoice WHERE company_id = ? AND status IN ('ISSUED','PARTIAL')"
              + " AND total_amount > paid_amount",
          "AP_OUTSTANDING",
          "SELECT doc_no AS docNo, supplier_id AS supplierId, due_date AS dueDate,"
              + " total_amount - paid_amount AS outstanding"
              + " FROM supplier_invoice WHERE company_id = ?"
              + " AND status NOT IN ('PAID','CANCELED') AND total_amount > paid_amount",
          "PROJECT_COST",
          "SELECT p.project_no AS projectNo, p.name AS projectName, p.budget_amount AS budget,"
              + " COALESCE(SUM(c.amount), 0) AS actualCost"
              + " FROM project p LEFT JOIN project_cost c ON c.project_id = p.id"
              + " WHERE p.company_id = ? GROUP BY p.id, p.project_no, p.name, p.budget_amount"
              + " ORDER BY p.id",
          "WORK_ORDER_STATUS",
          "SELECT status, COUNT(*) AS orderCount,"
              + " COALESCE(SUM(material_cost), 0) AS materialCost"
              + " FROM work_order WHERE company_id = ? GROUP BY status ORDER BY status",
          "AUDIT_SUMMARY",
          "SELECT action, COUNT(*) AS total FROM audit_log"
              + " WHERE DATE(created_at) <= ? GROUP BY action ORDER BY total DESC");

  private final ReportDefinitionMapper definitionMapper;
  private final ReportRunMapper runMapper;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final AuditFacade auditFacade;

  @Transactional(readOnly = true)
  public List<ReportDefinition> definitions() {
    return definitionMapper.findAll();
  }

  @Transactional
  public ReportRunView run(String code, long companyId, LocalDate asOf, long operatorId) {
    ReportDefinition definition =
        definitionMapper
            .findByCode(code == null ? null : code.trim().toUpperCase())
            .filter(value -> "ACTIVE".equals(value.getStatus()))
            .orElseThrow(() -> new BizException(ErrorCode.REPORT_DEFINITION_NOT_FOUND));
    String sql = QUERIES.get(definition.getQueryKey());
    if (sql == null) {
      throw new BizException(ErrorCode.REPORT_DEFINITION_NOT_FOUND, "报表查询未实现");
    }
    long started = System.nanoTime();
    List<Map<String, Object>> rows;
    try {
      if ("AUDIT_SUMMARY".equals(definition.getQueryKey())) {
        rows = jdbcTemplate.queryForList(sql, java.sql.Date.valueOf(asOfOrToday(asOf)));
      } else if (asOf != null
          && ("AR_AGING".equals(definition.getQueryKey())
              || "AP_OUTSTANDING".equals(definition.getQueryKey()))) {
        rows =
            jdbcTemplate.queryForList(
                sql + " AND due_date <= ? ORDER BY due_date",
                companyId,
                java.sql.Date.valueOf(asOf));
      } else {
        rows = jdbcTemplate.queryForList(sql, companyId);
      }
    } catch (RuntimeException ex) {
      ReportRun failed = new ReportRun();
      failed.setDefinitionId(definition.getId());
      failed.setCompanyId(companyId);
      failed.setAsOf(asOf);
      failed.setStatus("FAILED");
      failed.setRowCount(0);
      failed.setErrorMessage(truncate(ex.getMessage()));
      failed.setDurationMs((System.nanoTime() - started) / 1_000_000);
      failed.setRequestedBy(operatorId);
      runMapper.insert(failed);
      throw ex;
    }
    long duration = (System.nanoTime() - started) / 1_000_000;
    ReportRun run = new ReportRun();
    run.setDefinitionId(definition.getId());
    run.setCompanyId(companyId);
    run.setAsOf(asOf);
    run.setStatus("SUCCESS");
    run.setRowCount(rows.size());
    try {
      run.setResultJson(objectMapper.writeValueAsString(rows));
    } catch (Exception ex) {
      throw new BizException(ErrorCode.REPORT_DEFINITION_NOT_FOUND, "报表序列化失败");
    }
    run.setDurationMs(duration);
    run.setRequestedBy(operatorId);
    runMapper.insert(run);
    auditFacade.record(
        AuditAction.REPORT_RUN,
        AuditTargetType.REPORT_RUN,
        run.getId(),
        Map.of("code", definition.getCode(), "companyId", companyId, "rows", rows.size()));
    return view(run, definition);
  }

  @Transactional(readOnly = true)
  public PageResult<ReportRunView> pageRuns(int page, int size, Long companyId) {
    PageQuery query = PageQuery.of(page, size);
    return PageResult.of(
        runMapper.findPage(companyId, query.offset(), query.size()),
        runMapper.countBy(companyId),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public ReportRunView detail(long runId) {
    ReportRun run =
        runMapper
            .findById(runId)
            .orElseThrow(() -> new BizException(ErrorCode.REPORT_RUN_NOT_FOUND));
    ReportDefinition definition =
        definitionMapper
            .findById(run.getDefinitionId())
            .orElseThrow(() -> new BizException(ErrorCode.REPORT_DEFINITION_NOT_FOUND));
    return view(run, definition);
  }

  @Transactional(readOnly = true)
  public Csv csv(long runId) {
    ReportRun run =
        runMapper
            .findById(runId)
            .orElseThrow(() -> new BizException(ErrorCode.REPORT_RUN_NOT_FOUND));
    ReportDefinition definition =
        definitionMapper
            .findById(run.getDefinitionId())
            .orElseThrow(() -> new BizException(ErrorCode.REPORT_DEFINITION_NOT_FOUND));
    List<Map<String, Object>> rows;
    try {
      rows =
          run.getResultJson() == null
              ? List.of()
              : objectMapper.readValue(
                  run.getResultJson(), new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception ex) {
      throw new BizException(ErrorCode.REPORT_RUN_NOT_FOUND, "报表结果解析失败");
    }
    StringBuilder csv = new StringBuilder();
    List<String> headers = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      for (String key : row.keySet()) {
        if (!headers.contains(key)) {
          headers.add(key);
        }
      }
    }
    csv.append(String.join(",", headers)).append("\n");
    for (Map<String, Object> row : rows) {
      List<String> cells = new ArrayList<>();
      for (String header : headers) {
        cells.add(escape(row.get(header)));
      }
      csv.append(String.join(",", cells)).append("\n");
    }
    return new Csv(definition.getCode() + "-run" + runId + ".csv", csv.toString().getBytes(StandardCharsets.UTF_8));
  }

  private ReportRunView view(ReportRun run, ReportDefinition definition) {
    return new ReportRunView(
        run.getId(),
        definition.getId(),
        definition.getCode(),
        definition.getName(),
        run.getCompanyId(),
        run.getAsOf(),
        run.getStatus(),
        run.getRowCount() == null ? 0 : run.getRowCount(),
        run.getDurationMs(),
        run.getCreatedAt());
  }

  private static LocalDate asOfOrToday(LocalDate asOf) {
    return asOf == null ? LocalDate.now() : asOf;
  }

  static String escape(Object value) {
    if (value == null) {
      return "";
    }
    String text = value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
    // CSV 公式注入：Excel/Sheets 会把 = + - @ 开头的单元格当公式执行，前置单引号强制为文本。
    if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
      text = "'" + text;
    }
    if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
      return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    return text;
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 500 ? value : value.substring(0, 500);
  }

  /** CSV 下载结果。 */
  public record Csv(String filename, byte[] content) {}
}
