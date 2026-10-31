package com.spe.context.evaluation.internal.scoring;

import com.spe.integration.support.TestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.internal.task.TaskDraftCommand;
import com.spe.context.evaluation.internal.task.TaskService;
import com.spe.integration.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * W4.3 Excel 导入（任务级，13 §E17）：模板形状、幂等覆盖、**逐格**错误的行列定位、跳过已提交、服务层边界。
 *
 * <p>用**真实的 xlsx 字节流**（EasyExcel 写 → 服务读）而不是 mock：13 §R10 要求先实测"库到底怎么读"，
 * 而"表头写错""数字被读成字符串"这些事故只在真流上才暴露。
 */
class ScoreImportServiceIntegrationTest extends AbstractIntegrationTest {

  private static final AtomicInteger PERIOD_SEQ = new AtomicInteger(2111);

  @Autowired private ScoreImportService importService;
  @Autowired private ScoringService scoringService;
  @Autowired private TaskService taskService;

  @Test
  @DisplayName("W4.3 导入模板：第一列供应商编码、其余每列一个指标编码（13 个），行 = 任务的供应商")
  void templateHasSupplierRowsAndIndicatorCodeColumns() {
    Fixture fixture = publishedTask(2);

    byte[] template = importService.buildTemplate(fixture.taskId(), fixture.evaluatorId());
    HeadersAndRows parsed = readAll(template);

    assertThat(parsed.headers().get(0)).isEqualTo(ScoreImportService.SUPPLIER_CODE_HEADER);
    assertThat(parsed.headers().values())
        .as("列 = 该任务模板版本的全部指标编码（附录 A 的 13 个）")
        .contains("IQC_PASS_RATE", "OTD_RATE", "COST_DOWN_RATE", "ISSUE_RESPONSE")
        .hasSize(14);
    assertThat(parsed.rows())
        .as("行 = 任务里的供应商（本用例 2 家）")
        .hasSize(2)
        .allSatisfy(row -> assertThat(row.get(0)).isNotBlank());
    assertThat(parsed.rows().stream().map(row -> row.get(0)))
        .containsExactlyInAnyOrder(fixture.supplierCode(0), fixture.supplierCode(1));
  }

  @Test
  @DisplayName("W4.3 导入写入：来源 IMPORT + 批次号，且重复导入**覆盖**同一格（不新增行）")
  void importWritesThenOverwritesIdempotently() {
    Fixture fixture = publishedTask(1);
    long sheetId = fixture.sheetIdOfQuality(fixture.supplierId(0));
    Map<String, String> values = new LinkedHashMap<>();
    values.put("IQC_PASS_RATE", "98.50");
    values.put("QMS_AUDIT_SCORE", "92");

    ImportReport first =
        importXlsx(
            fixture.taskId(), xlsxOneRow(fixture.headers(), fixture.row(fixture.supplierCode(0), values)), "BATCH-1");

    assertThat(first.writtenCells()).as("写了两格").isEqualTo(2);
    assertThat(first.skippedCells()).isZero();
    assertThat(fixture.sourceOf(sheetId, "IQC_PASS_RATE")).isEqualTo("IMPORT");
    assertThat(fixture.batchOf(sheetId, "IQC_PASS_RATE")).isEqualTo("BATCH-1");
    assertThat(fixture.scoreValueOf(sheetId, "IQC_PASS_RATE")).as("换算分随写随落（E8）").isEqualByComparingTo("98.50");
    long rowsAfterFirst = fixture.scoreRowCount();

    Map<String, String> updated = new LinkedHashMap<>();
    updated.put("IQC_PASS_RATE", "90.00");
    importXlsx(
        fixture.taskId(), xlsxOneRow(fixture.headers(), fixture.row(fixture.supplierCode(0), updated)), "BATCH-2");

    assertThat(fixture.scoreValueOf(sheetId, "IQC_PASS_RATE"))
        .as("覆盖而不是新增")
        .isEqualByComparingTo("90.00");
    assertThat(fixture.batchOf(sheetId, "IQC_PASS_RATE")).as("批次号更新为最后一次").isEqualTo("BATCH-2");
    assertThat(fixture.scoreRowCount()).as("行数不变").isEqualTo(rowsAfterFirst);
  }

  @Test
  @DisplayName("W4.3 逐格校验：未知表头列 / 供应商不在任务内 / 数值越界 一律带行列定位，且**整批不写**")
  void importReportsCellErrorsAndWritesNothing() {
    Fixture fixture = publishedTask(1);
    List<String> headers = new ArrayList<>(fixture.headers());
    headers.add("NOT_A_CODE");
    Map<String, String> values = new LinkedHashMap<>();
    values.put("IQC_PASS_RATE", "120"); // RATIO 越界（01 §6.6 录入时拒绝；>100 的封顶只留给历史/API）
    values.put("QMS_AUDIT_SCORE", "92,5"); // 不是合法数字

    List<List<Object>> rows = new ArrayList<>();
    rows.add(rowOf(headers, fixture.supplierCode(0), values));
    rows.add(rowOf(headers, "NO-SUCH-SUPPLIER", Map.of()));

    assertThatThrownBy(
            () -> importXlsx(fixture.taskId(), xlsx(headers, rows), "BATCH-ERR"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .as("逐格错误走 2027（13 §E20）")
        .isEqualTo(ErrorCode.IMPORT_CELL_INVALID);
    assertThatThrownBy(
            () -> importXlsx(fixture.taskId(), xlsx(headers, rows), "BATCH-ERR"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("第1行")
        .hasMessageContaining("NOT_A_CODE")
        .hasMessageContaining("不是合法数字")
        .hasMessageContaining("第2行")
        .hasMessageContaining("该供应商不在本任务内");
    assertThat(fixture.scoreRowCount()).as("整批不写：一格都没落库").isZero();
  }

  @Test
  @DisplayName("W4.3 服务层边界（04-072）：导入不过 DTO，三位小数在服务层被拦（不落库、不改精度）")
  void importRejectsPrecisionBeyondTwoDecimals() {
    Fixture fixture = publishedTask(1);
    Map<String, String> values = Map.of("QMS_AUDIT_SCORE", "92.123");

    assertThatThrownBy(
            () ->
                importXlsx(
                    fixture.taskId(),
                    xlsxOneRow(fixture.headers(), fixture.row(fixture.supplierCode(0), values)),
                    "BATCH-PRECISION"))
        .isInstanceOf(BizException.class)
        // 钉**错误码**而不是只钉 message：M9 变异（去掉服务层校验）会让这条路径变成 2025
        // （ScoreWriter 里换算时才抛），只断言 message 的话两种路径都能通过 —— 变异测试抓出来的
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.IMPORT_CELL_INVALID);
    assertThatThrownBy(
            () ->
                importXlsx(
                    fixture.taskId(),
                    xlsxOneRow(fixture.headers(), fixture.row(fixture.supplierCode(0), values)),
                    "BATCH-PRECISION"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("最多两位小数");
    assertThat(fixture.scoreRowCount()).isZero();
  }

  @Test
  @DisplayName("P5 导入错误明细有上限：>1000 条时只保留前 1000 条并提示，整批仍不写")
  void importErrorsAreCappedInMemory() {
    Fixture fixture = publishedTask(1);
    List<String> headers = List.of(ScoreImportService.SUPPLIER_CODE_HEADER, "IQC_PASS_RATE");
    List<List<Object>> rows = new ArrayList<>();
    for (int i = 0; i < 1200; i++) {
      rows.add(List.of(fixture.supplierCode(0), "abc"));
    }

    BizException failure =
        catchThrowableOfType(
            () -> importXlsx(fixture.taskId(), xlsx(headers, rows), "BATCH-CAP"),
            BizException.class);

    assertThat(failure).as("逐格错误仍走 2027").isNotNull();
    assertThat(failure.errorCode()).isEqualTo(ErrorCode.IMPORT_CELL_INVALID);
    assertThat(failure.getMessage())
        .as("总数如实统计，明细只保留前 1000 条")
        .contains("共 1200 条")
        .contains("仅保留前 " + ScoreImportService.MAX_ERRORS + " 条明细");
    assertThat(fixture.scoreRowCount()).as("有错仍整批不写").isZero();

    List<List<Object>> small = List.of(
        List.of(fixture.supplierCode(0), "abc"), List.of(fixture.supplierCode(0), "def"));
    BizException smallFailure =
        catchThrowableOfType(
            () -> importXlsx(fixture.taskId(), xlsx(headers, small), "BATCH-SMALL"),
            BizException.class);
    assertThat(smallFailure).isNotNull();
    assertThat(smallFailure.getMessage())
        .as("1000 条以内不出现截断提示")
        .doesNotContain("仅保留前");
  }

  @Test
  @DisplayName("W4.3 已提交的评分单：跳过而不是报错（13 §E17：跳过不是错误）")
  void importSkipsNonScoringSheets() {
    Fixture fixture = publishedTask(1);
    long sheetId = fixture.sheetIdOfQuality(fixture.supplierId(0));
    jdbcTemplate.update("update sheet set status = 'SUBMITTED' where id = ?", sheetId);

    ImportReport report =
        importXlsx(
            fixture.taskId(),
            xlsxOneRow(fixture.headers(), fixture.row(fixture.supplierCode(0), Map.of("QMS_AUDIT_SCORE", "92"))),
            "BATCH-SKIP");

    assertThat(report.writtenCells()).isZero();
    assertThat(report.skippedCells()).as("这一格被跳过（已提交的评分单不可写）").isEqualTo(1);
    assertThat(fixture.scoreRowCount()).isZero();
  }

  // ---------- 夹具与工具 ----------

  private record HeadersAndRows(Map<Integer, String> headers, List<Map<Integer, String>> rows) {}

  private static HeadersAndRows readAll(byte[] xlsx) {
    Map<Integer, String> headers = new LinkedHashMap<>();
    List<Map<Integer, String>> rows = new ArrayList<>();
    EasyExcel.read(new ByteArrayInputStream(xlsx))
        .headRowNumber(1)
        .registerReadListener(
            new AnalysisEventListener<Map<Integer, String>>() {
              @Override
              public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                headers.putAll(headMap);
              }

              @Override
              public void invoke(Map<Integer, String> data, AnalysisContext context) {
                rows.add(new LinkedHashMap<>(data));
              }

              @Override
              public void doAfterAllAnalysed(AnalysisContext context) {}
            })
        .sheet(0)
        .doRead();
    return new HeadersAndRows(headers, rows);
  }

  private static byte[] xlsx(List<String> headers, List<List<Object>> rows) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    EasyExcel.write(out).head(headers.stream().map(List::of).toList()).sheet("导入").doWrite(rows);
    return out.toByteArray();
  }

  private static byte[] xlsxOneRow(List<String> headers, List<Object> row) {
    return xlsx(headers, List.of(row));
  }

  @Test
  @DisplayName("04-096：不参与该任务的人取模板 / 导入一律 4002（越权实测见 03 §34）")
  void strangerCannotReadTemplateNorImport() {
    Fixture fixture = publishedTask(1);
    // 发布**之后**才建的人：他不在任何 sheet 上 ⇒ 与这个任务毫无关系
    long stranger = insertUser("w4-imp-stranger-" + fixture.taskId(), "EVALUATOR", "QUALITY");

    assertThatThrownBy(() -> importService.buildTemplate(fixture.taskId(), stranger))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SHEET_NOT_ACCESSIBLE);

    Map<String, String> values = new LinkedHashMap<>();
    values.put("IQC_PASS_RATE", "90");
    byte[] bytes = xlsxOneRow(fixture.headers(), fixture.row(fixture.supplierCode(0), values));
    assertThatThrownBy(
            () ->
                importService.importScores(
                    fixture.taskId(), new ByteArrayInputStream(bytes), "BATCH-STRANGER", stranger))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.SHEET_NOT_ACCESSIBLE);
  }

  @Test
  @DisplayName("04-096：填了不属于自己维度的格子 ⇒ 报错（带列名）且整批不写")
  void cellsOutsideMySheetsAreRejected() {
    Fixture fixture = publishedTask(1);
    long before = fixture.scoreRowCount();
    // 「交付」维度的指标：本用例里该维度**没有指派给质量评估人**（发布时该部门无人 ⇒ 置未指派）
    Map<String, String> values = new LinkedHashMap<>();
    values.put("OTD_RATE", "98");
    byte[] bytes = xlsxOneRow(fixture.headers(), fixture.row(fixture.supplierCode(0), values));

    assertThatThrownBy(() -> importXlsx(fixture.taskId(), bytes, "BATCH-ALIEN"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("不在指派给你的评分单里");
    assertThat(fixture.scoreRowCount()).as("有错则整批不写").isEqualTo(before);
  }

  @Test
  @DisplayName("04-096：提交时的 stale 回写只纠分值，**不动**来源与批次号（03 §34 P1-2）")
  void submittingCorrectsValueButKeepsImportProvenance() {
    Fixture fixture = publishedTask(1);
    long sheetId = fixture.sheetIdOfQuality(fixture.supplierId(0));
    Map<String, String> values = new LinkedHashMap<>();
    values.put("IQC_PASS_RATE", "98.50");
    values.put("QUALITY_ACCIDENT", "100");
    values.put("IQC_ON_TIME_RATE", "96.00");
    values.put("QMS_AUDIT_SCORE", "92");
    importXlsx(
        fixture.taskId(),
        xlsxOneRow(fixture.headers(), fixture.row(fixture.supplierCode(0), values)),
        "BATCH-KEEP");
    assertThat(fixture.sourceOf(sheetId, "IQC_PASS_RATE")).isEqualTo("IMPORT");

    // 制造 stale：把一格的 score_value 改成错值（等价于"历史脏行/有人绕过 ScoreWriter 写库"）
    jdbcTemplate.update(
        "update score sc join indicator i on i.id = sc.indicator_id set sc.score_value = 1.00"
            + " where sc.sheet_id = ? and i.code = 'IQC_PASS_RATE'",
        sheetId);

    scoringService.submit(sheetId, fixture.evaluatorId(), sheetVersion(sheetId));

    assertThat(fixture.scoreValueOf(sheetId, "IQC_PASS_RATE"))
        .as("回写把分值纠回快照口径")
        .isEqualByComparingTo(new BigDecimal("98.50"));
    assertThat(fixture.sourceOf(sheetId, "IQC_PASS_RATE")).as("来源仍是 IMPORT，不该被改成 MANUAL").isEqualTo("IMPORT");
    assertThat(fixture.batchOf(sheetId, "IQC_PASS_RATE")).as("批次号不该被清空").isEqualTo("BATCH-KEEP");
  }

  private int sheetVersion(long sheetId) {
    Integer version =
        jdbcTemplate.queryForObject("select version from sheet where id = ?", Integer.class, sheetId);
    return version == null ? 0 : version;
  }

  /**
   * 服务接口收的是 InputStream（W5 的 multipart 直接用），测试这里统一包一层。
   *
   * <p>`operatorId` **现查库**取该任务「质量」维度的被指派人（04-096 起导入要求"这一格归我"）：
   * 从数据里取而不是写常量，用例重复执行时也不会因为 id 变化而失效。
   */
  private ImportReport importXlsx(long taskId, byte[] bytes, String batch) {
    return importService.importScores(
        taskId, new ByteArrayInputStream(bytes), batch, qualityAssigneeOf(taskId));
  }

  private long qualityAssigneeOf(long taskId) {
    Long id =
        jdbcTemplate.queryForObject(
            "select s.assignee_id from sheet s join task_supplier ts on ts.id = s.task_supplier_id"
                + " join dimension d on d.id = s.dimension_id"
                + " where ts.task_id = ? and d.name = '质量' limit 1",
            Long.class,
            taskId);
    return id == null ? -1 : id;
  }

  private static List<Object> rowOf(List<String> headers, String supplierCode, Map<String, String> values) {
    List<Object> row = new ArrayList<>();
    row.add(supplierCode);
    for (int i = 1; i < headers.size(); i++) {
      row.add(values.get(headers.get(i)));
    }
    return row;
  }

  private final class Fixture {

    private final long taskId;
    private final List<Long> supplierIds;
    private final List<String> supplierCodes;
    /** 「质量」维度的被指派人：导入/模板的 operatorId 用它（04-096 起这两条路径要判"归不归我"）。 */
    private final long evaluatorId;

    private Fixture(long taskId, List<Long> supplierIds, List<String> supplierCodes, long evaluatorId) {
      this.taskId = taskId;
      this.supplierIds = supplierIds;
      this.supplierCodes = supplierCodes;
      this.evaluatorId = evaluatorId;
    }

    long taskId() {
      return taskId;
    }

    long evaluatorId() {
      return evaluatorId;
    }

    long supplierId(int index) {
      return supplierIds.get(index);
    }

    String supplierCode(int index) {
      return supplierCodes.get(index);
    }

    List<String> headers() {
      Long versionId =
          jdbcTemplate.queryForObject(
              "select template_version_id from eval_task where id = ?", Long.class, taskId);
      List<String> codes = new ArrayList<>();
      codes.add(ScoreImportService.SUPPLIER_CODE_HEADER);
      jdbcTemplate
          .queryForList(
              "select i.code from indicator i join dimension d on d.id = i.dimension_id"
                  + " where d.template_version_id = ? order by d.sort_no, i.sort_no",
              String.class,
              versionId)
          .forEach(codes::add);
      return codes;
    }

    List<Object> row(String supplierCode, Map<String, String> values) {
      return rowOf(headers(), supplierCode, values);
    }

    long sheetIdOfQuality(long supplierId) {
      Long id =
          jdbcTemplate.queryForObject(
              "select s.id from sheet s join task_supplier ts on ts.id = s.task_supplier_id"
                  + " join dimension d on d.id = s.dimension_id"
                  + " where ts.task_id = ? and ts.supplier_id = ? and d.name = '质量'",
              Long.class,
              taskId,
              supplierId);
      return id == null ? -1 : id;
    }

    String sourceOf(long sheetId, String code) {
      return scalar(sheetId, code, "sc.source");
    }

    String batchOf(long sheetId, String code) {
      return scalar(sheetId, code, "sc.import_batch");
    }

    BigDecimal scoreValueOf(long sheetId, String code) {
      return jdbcTemplate.queryForObject(
          "select sc.score_value from score sc join indicator i on i.id = sc.indicator_id"
              + " where sc.sheet_id = ? and i.code = ?",
          BigDecimal.class,
          sheetId,
          code);
    }

    private String scalar(long sheetId, String code, String column) {
      return jdbcTemplate.queryForObject(
          "select " + column + " from score sc join indicator i on i.id = sc.indicator_id"
              + " where sc.sheet_id = ? and i.code = ?",
          String.class,
          sheetId,
          code);
    }

    long scoreRowCount() {
      Long count =
          jdbcTemplate.queryForObject(
              "select count(*) from score sc join sheet s on s.id = sc.sheet_id"
                  + " join task_supplier ts on ts.id = s.task_supplier_id where ts.task_id = ?",
              Long.class,
              taskId);
      return count == null ? -1 : count;
    }
  }

  private Fixture publishedTask(int supplierCount) {
    int year = PERIOD_SEQ.getAndIncrement();
    String suffix = String.valueOf(year);
    List<Long> supplierIds = new ArrayList<>();
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < supplierCount; i++) {
      String code = "W4-IMP-" + suffix + "-" + i;
      codes.add(code);
      supplierIds.add(insertSupplier(code));
    }
    long approver = insertUser("w4-imp-approver-" + suffix, "MANAGER", "OTHER");
    long evaluator = insertUser("w4-imp-eval-" + suffix, "EVALUATOR", "QUALITY");
    Long versionId =
        jdbcTemplate.queryForObject(
            "select v.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'",
            Long.class);
    Long templateId =
        jdbcTemplate.queryForObject(
            "select t.id from template_version v join template t on t.id = v.template_id"
                + " where t.name = '季度绩效默认模板' and v.version_no = 1 and v.status = 'PUBLISHED'",
            Long.class);
    long taskId =
        taskService.createDraft(
            new TaskDraftCommand(
                "W4 导入任务-" + suffix,
                year,
                1,
                versionId == null ? -1 : templateId,
                versionId == null ? -1 : versionId,
                LocalDate.of(year, 12, 31),
                supplierIds,
                List.of(approver)));
    taskService.publish(taskId, 0);
    // 只把「质量」维度改派给该评估人，其余维度保持发布时的自动指派（导入只关心状态，不关心谁）
    jdbcTemplate.update(
        "update sheet s join task_supplier ts on ts.id = s.task_supplier_id"
            + " join dimension d on d.id = s.dimension_id"
            + " set s.assignee_id = ?, s.status = 'SCORING' where ts.task_id = ? and d.name = '质量'",
        evaluator,
        taskId);
    return new Fixture(taskId, supplierIds, codes, evaluator);
  }

  private long insertSupplier(String code) { return TestFixtures.insertSupplier(jdbcTemplate, code, "供应商 " + code, "W4 导入测试"); }

  private long insertUser(String username, String role, String department) { return TestFixtures.insertUser(jdbcTemplate, username, role, department); }
}
