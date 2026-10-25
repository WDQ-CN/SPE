package com.spe.context.evaluation.internal.scoring;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.exception.ExcelAnalysisException;
import com.alibaba.excel.exception.ExcelCommonException;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.api.ScoreSource;
import com.spe.context.evaluation.api.SheetStatus;
import com.spe.context.evaluation.internal.scoring.calculator.ScoreStrategies;
import com.spe.context.evaluation.internal.sheet.Sheet;
import com.spe.context.evaluation.internal.sheet.SheetMapper;
import com.spe.context.evaluation.internal.task.EvalTask;
import com.spe.context.evaluation.internal.task.EvalTaskMapper;
import com.spe.context.evaluation.internal.task.TaskSupplier;
import com.spe.context.evaluation.internal.task.TaskSupplierMapper;
import com.spe.context.supplier.api.SupplierBrief;
import com.spe.context.supplier.api.SupplierQueryFacade;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.IndicatorSnapshot;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Excel 导入（01 E3 / 13 §E17：**任务级**）：行 = 供应商、列 = 该任务全部**指标编码**。
 *
 * <p>三条与规格直接对应的要点：
 * <ol>
 *   <li><b>逐格校验</b>：供应商在任务内、指标编码在表头里、数值按 01 §6.6 —— 每格独立判定，错误带**行列位置**；
 *   <li><b>只写「评分中」的评分单</b>：待指派/已提交的格子**跳过**并计入跳过数（跳过不是错误，13 §E17）；
 *   <li><b>覆盖而非新增</b>：写入走 {@link ScoreWriter} 的 upsert，同一 `(sheet_id, indicator_id)` 覆盖，
 *       并记下本次批次号。
 * </ol>
 *
 * <p>⚠️ 导入**不过 DTO**：`@Digits`/`@Size` 在这条路径上完全不起作用（04-072），所以边界必须在服务层拦——
 * 本类用与打分页**同一个**换算策略（{@link ScoreStrategies}）判定，口径不会漂移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreImportService {

  /** 模板第一列的标题：用**编码**定位供应商（导入是即时对照，编码改过就按新编码填）。 */
  public static final String SUPPLIER_CODE_HEADER = "供应商编码";

  /**
   * 逐格错误明细的内存上限（P5）：一个百万行 × 13 列的 xlsx 可以产生千万条错误，
   * 在"整批不写"之前先把内存撑爆。保留前 1000 条明细，总数仍然如实统计并写进 message。
   */
  static final int MAX_ERRORS = 1000;

  private final EvalTaskMapper taskMapper;
  private final TaskSupplierMapper taskSupplierMapper;
  private final SheetMapper sheetMapper;
  private final TemplateSnapshotFacade templateSnapshotFacade;
  private final SupplierQueryFacade supplierQueryFacade;
  private final ScoreWriter scoreWriter;
  private final AuditFacade auditFacade;

  /**
   * 生成批次号（每次导入生成一个）：格式 `IMP-yyyyMMddHHmmssSSS`，落进 `score.import_batch` 供追溯。
   *
   * <p>放在服务里而不是 Controller：批次号是**数据口径**（02 §3.1 的列注释就写着它），不是接口细节。
   */
  public static String newBatchNo() {
    return "IMP-"
        + java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
  }

  /**
   * 生成导入模板：第 1 列供应商编码，其余每列一个指标编码（按快照的维度与指标顺序）。
   *
   * @param operatorId 调用者：必须**参与该任务**，否则 4002（04-096——原先没有这一层，任意评估人
   *     枚举 taskId 就能读走别人的供应商与指标清单）
   */
  @Transactional(readOnly = true)
  public byte[] buildTemplate(long taskId, long operatorId) {
    EvalTask task = requireTask(taskId);
    requireParticipant(taskId, operatorId);
    // 一次取回本任务的评估单（P2-4：suppliersOf 与下面的行构造共用，不再各查一遍）
    List<TaskSupplier> taskSuppliers = taskSupplierMapper.findByTaskId(taskId);
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(task.getTemplateVersionId());
    List<String> headers = new ArrayList<>();
    headers.add(SUPPLIER_CODE_HEADER);
    for (DimensionSnapshot dimension : snapshot.dimensions()) {
      for (IndicatorSnapshot indicator : dimension.indicators()) {
        headers.add(indicator.code());
      }
    }
    Map<Long, SupplierBrief> suppliers = suppliersOf(taskSuppliers);
    List<List<String>> head = headers.stream().map(List::of).toList();
    List<List<Object>> rows = new ArrayList<>();
    for (TaskSupplier taskSupplier : taskSuppliers) {
      SupplierBrief supplier = suppliers.get(taskSupplier.getSupplierId());
      List<Object> row = new ArrayList<>();
      row.add(supplier == null ? String.valueOf(taskSupplier.getSupplierId()) : supplier.code());
      for (int i = 1; i < headers.size(); i++) {
        row.add(null);
      }
      rows.add(row);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    EasyExcel.write(out).head(head).sheet("评分导入").doWrite(rows);
    return out.toByteArray();
  }

  /**
   * 导入（01 E3）：解析 → 逐格校验 → 按评分单批量 upsert → 审计。
   *
   * @param batchNo 批次号（调用方生成；写进 `score.import_batch`）
   */
  @Transactional
  public ImportReport importScores(long taskId, InputStream in, String batchNo, long operatorId) {
    EvalTask task = requireTask(taskId);
    requireParticipant(taskId, operatorId);
    // 取数收敛（03 §38 的 P2-4）：原来 `suppliersOf` 与 `ImportContext.of` **各查一次** task_supplier，
    // 而且后者还按评估单**逐个**查评分单（1+N，SQL 条数 = 1 + 供应商数）。
    // 现在两条查询就够：task_supplier 一次、sheet 一次（SheetMapper.findByTaskId 本就是为看板准备的）
    List<TaskSupplier> taskSuppliers = taskSupplierMapper.findByTaskId(taskId);
    ImportContext context =
        ImportContext.of(
            task,
            taskSuppliers,
            sheetMapper.findByTaskId(taskId),
            templateSnapshotFacade,
            suppliersOf(taskSuppliers),
            operatorId);
    Collector collector = new Collector(context);
    try {
      EasyExcel.read(in).sheet(0).headRowNumber(1).registerReadListener(collector).doRead();
    } catch (RuntimeException ex) {
      // ⚠️ P2-6（03 §38）：这一层原先是 `catch (RuntimeException)` ⇒ 一律翻成 2026「文件无法解析」，
      // 于是 Collector 里的空指针/越界/非法状态也被洗成"用户文件坏了"，**掩盖真缺陷**、还让 5001 永远不出现。
      // 现在反过来：只认"文件级解析失败"那一小撮，其余**原样上浮**（认不出来 ⇒ 落 5001，那是它应得的信号）。
      if (!isFileLevelParseFailure(ex)) {
        throw ex;
      }
      // 文件本身读不出内容（坏 zip、表头缺失、类型转换不了）：这是"文件级"错误（2026），
      // 格子级错误走 errors 列表，两层的口径不同
      log.warn("Excel 导入文件无法解析：taskId={}，批次={}", taskId, batchNo, ex);
      throw new BizException(ErrorCode.IMPORT_FILE_INVALID, "文件无法解析为 xlsx：如不确定请重新下载导入模板");
    }

    if (collector.totalErrors > 0) {
      // 全或无：有任何一个格子不合法就整批不写（01 E3 的"逐格校验"+"批次号覆盖一次完整导入"）
      throw new BizException(ErrorCode.IMPORT_CELL_INVALID, summarize(collector));
    }
    int written = 0;
    for (Map.Entry<Long, Map<Long, BigDecimal>> entry : collector.valuesBySheet.entrySet()) {
      // ⚠️ 清空集合恒为空：01 §6.6/§6.7 规定"Excel 里的空格视为该格未填"，**不是**"删掉这一格"。
      // P2-15 的"显式 null = 清空"只属于手工打分那条 PUT（前端有 touchedIds 能区分"从没填"与"填过又清空"），
      // 表格文件里区分不了，所以这里一条都不删。
      written +=
          scoreWriter.save(entry.getKey(), entry.getValue(), List.of(), ScoreSource.IMPORT, batchNo, operatorId);
    }
    ImportReport report = new ImportReport(batchNo, written, collector.skippedCells);
    auditFacade.record(
        AuditAction.SHEET_IMPORT,
        AuditTargetType.TASK,
        taskId,
        Map.of("batch", batchNo, "written", report.writtenCells(), "skipped", report.skippedCells()));
    log.info(
        "Excel 导入完成：taskId={}，批次={}，写入 {} 格，跳过 {} 格",
        taskId,
        batchNo,
        report.writtenCells(),
        report.skippedCells());
    return report;
  }

  /**
   * 「文件级解析失败」的判据（03 §38 的 P2-6 的核心；包私有以便单测直接问它）。
   *
   * <p>两条规则，**顺序不能反**：
   * <ol>
   *   <li>cause 链里出现我们自己的代码缺陷（空指针 / 越界 / 非法状态 / 类型转换）⇒ 这不是文件问题。
   *       ⚠️ 必须看链而不是只看外层：EasyExcel 会把 listener 抛出的异常也包进
   *       {@code ExcelAnalysisException}，"换个 catch 的类型"判不出这一层。
   *   <li>否则只要链里含 EasyExcel 的解析异常或 IO 失败 ⇒ 认定是文件级失败，才配得上"文件无法解析"这句话。
   * </ol>
   * 两条都不沾的第三条路返回 false ⇒ 调用方**原样上浮**成 5001：宁多一个 5001，不可把代码缺陷洗成用户错误。
   */
  static boolean isFileLevelParseFailure(Throwable ex) {
    if (anyCauseMatches(ex, ScoreImportService::isCodeDefect)) {
      return false;
    }
    return anyCauseMatches(
        ex,
        cursor ->
            cursor instanceof ExcelAnalysisException
                || cursor instanceof ExcelCommonException
                || cursor instanceof IOException);
  }

  private static boolean isCodeDefect(Throwable cursor) {
    return cursor instanceof NullPointerException
        || cursor instanceof IndexOutOfBoundsException
        || cursor instanceof IllegalStateException
        || cursor instanceof ClassCastException;
  }

  /** 顺着 cause 链找一次（遇到自引用的 cause 就停，避免死循环）。 */
  private static boolean anyCauseMatches(Throwable ex, Predicate<Throwable> matcher) {
    for (Throwable cursor = ex; cursor != null; cursor = cursor.getCause()) {
      if (matcher.test(cursor)) {
        return true;
      }
      if (cursor.getCause() == cursor) {
        break;
      }
    }
    return false;
  }

  /** 把逐格错误拼成一条可读的 message（最多 10 条，其余折叠计数）——01 E3 的"可多条、带行列定位"。 */
  private static String summarize(Collector collector) {
    StringBuilder sb = new StringBuilder("导入内容有误：");
    collector.errors.stream()
        .limit(10)
        .forEach(
            error ->
                sb.append("第")
                    .append(error.row())
                    .append("行·")
                    .append(error.column())
                    .append(" ")
                    .append(error.message())
                    .append("；"));
    if (collector.totalErrors > 10) {
      sb.append("……共 ").append(collector.totalErrors).append(" 条");
    }
    if (collector.truncated) {
      sb.append("（错误过多，仅保留前 ").append(MAX_ERRORS).append(" 条明细）");
    }
    return sb.toString();
  }

  private EvalTask requireTask(long taskId) {
    return taskMapper.findById(taskId).orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
  }

  /**
   * 导入/取模板的**参与判据**（04-096）：调用者必须在该任务下**至少有一张**指派给自己的评分单。
   *
   * <p>为什么不是"逐张 sheet 判可见性"就够：模板与导入都是**任务级**的（01 E3：行 = 供应商、
   * 列 = 全部指标），形状上装不下"只给我的那一半"；所以先在入口处收紧"能不能碰这个任务"，
   * 再由 {@code Collector} 逐格判"这一格归不归我"。**两条都要**，缺一条都不够。
   */
  private void requireParticipant(long taskId, long operatorId) {
    if (sheetMapper.countMineInTask(taskId, operatorId) == 0) {
      throw new BizException(ErrorCode.SHEET_NOT_ACCESSIBLE);
    }
  }

  /** 由已取回的评估单推供应商集合（P2-4：不再自己查 task_supplier，避免同一个任务被读两遍）。 */
  private Map<Long, SupplierBrief> suppliersOf(List<TaskSupplier> taskSuppliers) {
    List<Long> supplierIds = taskSuppliers.stream().map(TaskSupplier::getSupplierId).toList();
    Map<Long, SupplierBrief> byId = new LinkedHashMap<>();
    if (!supplierIds.isEmpty()) {
      supplierQueryFacade.findByIds(supplierIds).forEach(brief -> byId.put(brief.id(), brief));
    }
    return byId;
  }

  /** 解析所需的全部索引：一次建好，避免逐格回查（13 §E14 的同一条经验）。 */
  private record ImportContext(
      Map<String, IndicatorSnapshot> indicatorsByCode,
      Map<Long, Long> dimensionByIndicator,
      Map<String, Long> supplierIdByCode,
      Map<Long, TaskSupplier> taskSupplierBySupplierId,
      Map<String, Sheet> sheetByTaskSupplierAndDimension,
      long operatorId) {

    static ImportContext of(
        EvalTask task,
        List<TaskSupplier> taskSuppliers,
        List<Sheet> sheetsOfTask,
        TemplateSnapshotFacade facade,
        Map<Long, SupplierBrief> suppliers,
        long operatorId) {
      TemplateSnapshot snapshot = facade.requirePublished(task.getTemplateVersionId());
      Map<String, IndicatorSnapshot> indicatorsByCode = new LinkedHashMap<>();
      Map<Long, Long> dimensionByIndicator = new LinkedHashMap<>();
      for (DimensionSnapshot dimension : snapshot.dimensions()) {
        for (IndicatorSnapshot indicator : dimension.indicators()) {
          indicatorsByCode.put(indicator.code(), indicator);
          dimensionByIndicator.put(indicator.id(), dimension.id());
        }
      }
      Map<String, Long> supplierIdByCode = new LinkedHashMap<>();
      suppliers.values().forEach(brief -> supplierIdByCode.put(brief.code(), brief.id()));

      Map<Long, TaskSupplier> taskSupplierBySupplierId = new LinkedHashMap<>();
      taskSuppliers.forEach(
          taskSupplier -> taskSupplierBySupplierId.put(taskSupplier.getSupplierId(), taskSupplier));
      // 评分单**一次取回**后在内存里按 (评估单, 维度) 建索引（P2-4：原先是 1+N 的逐评估单查询）
      Map<String, Sheet> sheets = new LinkedHashMap<>();
      for (Sheet sheet : sheetsOfTask) {
        sheets.put(sheet.getTaskSupplierId() + "-" + sheet.getDimensionId(), sheet);
      }
      return new ImportContext(
          indicatorsByCode,
          dimensionByIndicator,
          supplierIdByCode,
          taskSupplierBySupplierId,
          sheets,
          operatorId);
    }
  }

  /** EasyExcel 的无模型读取回调：收集错误与待写值，写完由调用方统一落库。 */
  private static final class Collector extends AnalysisEventListener<Map<Integer, String>> {

    private final ImportContext context;
    private final Map<Integer, String> headers = new LinkedHashMap<>();
    private final List<ImportReport.CellError> errors = new ArrayList<>();
    private final Map<Long, Map<Long, BigDecimal>> valuesBySheet = new LinkedHashMap<>();
    private int totalErrors;
    private boolean truncated;
    private int skippedCells;
    private int rowNumber;

    private void error(int row, String column, String message) {
      totalErrors++;
      if (errors.size() < MAX_ERRORS) {
        errors.add(new ImportReport.CellError(row, column, message));
      } else {
        truncated = true;
      }
    }

    private Collector(ImportContext context) {
      this.context = context;
    }

    @Override
    public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext analysisContext) {
      headers.putAll(headMap);
      headMap.forEach(
          (column, header) -> {
            if (column == 0 || header == null || header.isBlank()) {
              return;
            }
            if (!context.indicatorsByCode().containsKey(header.trim())) {
              // 未知列直接报错：静默忽略会让整列数据"消失"，而用户以为导进去了
              error(1, header, "表头里的指标编码不在该任务的模板版本里");
            }
          });
    }

    @Override
    public void invoke(Map<Integer, String> row, AnalysisContext analysisContext) {
      rowNumber = analysisContext.readRowHolder().getRowIndex() + 1;
      String supplierCode = row.get(0);
      if (supplierCode == null || supplierCode.isBlank()) {
        error(rowNumber, SUPPLIER_CODE_HEADER, "供应商编码为空");
        return;
      }
      Long supplierId = context.supplierIdByCode().get(supplierCode.trim());
      if (supplierId == null) {
        error(rowNumber, SUPPLIER_CODE_HEADER, "该供应商不在本任务内");
        return;
      }
      TaskSupplier taskSupplier = context.taskSupplierBySupplierId().get(supplierId);
      if (taskSupplier == null) {
        error(rowNumber, SUPPLIER_CODE_HEADER, "该供应商还没有评估单");
        return;
      }

      row.forEach(
          (column, raw) -> {
            if (column == 0 || raw == null || raw.isBlank()) {
              return; // 空格视为未填（01 §6.6），提交时才会拦
            }
            String header = headers.get(column);
            IndicatorSnapshot indicator =
                header == null ? null : context.indicatorsByCode().get(header.trim());
            if (indicator == null) {
              return; // 未知列已在表头处报过，不重复刷屏
            }
            Long dimensionId = context.dimensionByIndicator().get(indicator.id());
            Sheet sheet =
                context.sheetByTaskSupplierAndDimension().get(taskSupplier.getId() + "-" + dimensionId);
            if (sheet == null) {
              error(rowNumber, header, "该指标对应的评分单不存在");
              return;
            }
            if (sheet.getAssigneeId() == null || sheet.getAssigneeId() != context.operatorId()) {
              // 04-096：导入是**任务级**的（模板含全部列），但写入只能落在**指派给自己**的维度上。
              // 这里报错而不是像"待指派/已提交"那样静默跳过——"填了别人的格子"多半是拿错了表，
              // 咽下去就变成静默失效（CLAUDE 默认约定第 1 条）。
              error(rowNumber, header, "该指标不在指派给你的评分单里");
              return;
            }
            if (!SheetStatus.SCORING.name().equals(sheet.getStatus().name())) {
              skippedCells++; // 待指派/已提交：跳过不是错误（13 §E17）
              return;
            }
            BigDecimal value;
            try {
              value = new BigDecimal(raw.trim());
            } catch (NumberFormatException ex) {
              error(rowNumber, header, "不是合法数字：" + raw);
              return;
            }
            try {
              // 与打分页同一个策略：边界与精度只写一份（01 §6.6）
              ScoreStrategies.of(indicator).toHundred(value, indicator);
            } catch (IllegalArgumentException ex) {
              error(rowNumber, header, ex.getMessage());
              return;
            }
            valuesBySheet
                .computeIfAbsent(sheet.getId(), key -> new LinkedHashMap<>())
                .put(indicator.id(), value);
          });
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext analysisContext) {
      // 写入由调用方在解析结束后统一执行（先把整表的错误收集完，符合 01 E3 的"错误汇总一次返回"）
    }
  }
}
