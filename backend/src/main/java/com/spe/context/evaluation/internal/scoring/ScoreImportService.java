package com.spe.context.evaluation.internal.scoring;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
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
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(task.getTemplateVersionId());
    List<String> headers = new ArrayList<>();
    headers.add(SUPPLIER_CODE_HEADER);
    for (DimensionSnapshot dimension : snapshot.dimensions()) {
      for (IndicatorSnapshot indicator : dimension.indicators()) {
        headers.add(indicator.code());
      }
    }
    Map<Long, SupplierBrief> suppliers = suppliersOf(taskId);
    List<List<String>> head = headers.stream().map(List::of).toList();
    List<List<Object>> rows = new ArrayList<>();
    for (TaskSupplier taskSupplier : taskSupplierMapper.findByTaskId(taskId)) {
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
    ImportContext context =
        ImportContext.of(
            task, taskSupplierMapper, sheetMapper, templateSnapshotFacade, suppliersOf(taskId), operatorId);
    Collector collector = new Collector(context);
    try {
      EasyExcel.read(in).sheet(0).headRowNumber(1).registerReadListener(collector).doRead();
    } catch (RuntimeException ex) {
      // 不是可解析的 xlsx / 表头缺失：这一层失败属于"文件级"错误（2026），格子级错误走 errors 列表
      throw new BizException(ErrorCode.IMPORT_FILE_INVALID, "文件无法解析为 xlsx：如不确定请重新下载导入模板");
    }

    if (!collector.errors.isEmpty()) {
      // 全或无：有任何一个格子不合法就整批不写（01 E3 的"逐格校验"+"批次号覆盖一次完整导入"）
      throw new BizException(ErrorCode.IMPORT_CELL_INVALID, summarize(collector.errors));
    }
    int written = 0;
    for (Map.Entry<Long, Map<Long, BigDecimal>> entry : collector.valuesBySheet.entrySet()) {
      written += scoreWriter.save(entry.getKey(), entry.getValue(), ScoreSource.IMPORT, batchNo, operatorId);
    }
    ImportReport report = new ImportReport(batchNo, written, collector.skippedCells);
    auditFacade.record(
        AuditAction.SHEET_IMPORT,
        AuditTargetType.TASK,
        taskId,
        Map.of("batch", batchNo, "written", report.writtenCells(), "skipped", report.skippedCells()));
    log.info(
        "Excel 导入完成：taskId={}，批次={}，写入 {} 格，跳过 {} 格，错误 {} 条",
        taskId,
        batchNo,
        report.writtenCells(),
        report.skippedCells());
    return report;
  }

  /** 把逐格错误拼成一条可读的 message（最多 10 条，其余折叠计数）——01 E3 的"可多条、带行列定位"。 */
  private static String summarize(List<ImportReport.CellError> errors) {
    StringBuilder sb = new StringBuilder("导入内容有误：");
    errors.stream()
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
    if (errors.size() > 10) {
      sb.append("……共 ").append(errors.size()).append(" 条");
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

  private Map<Long, SupplierBrief> suppliersOf(long taskId) {
    List<Long> supplierIds =
        taskSupplierMapper.findByTaskId(taskId).stream().map(TaskSupplier::getSupplierId).toList();
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
        TaskSupplierMapper taskSupplierMapper,
        SheetMapper sheetMapper,
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
      Map<String, Sheet> sheets = new LinkedHashMap<>();
      for (TaskSupplier taskSupplier : taskSupplierMapper.findByTaskId(task.getId())) {
        taskSupplierBySupplierId.put(taskSupplier.getSupplierId(), taskSupplier);
        for (Sheet sheet : sheetMapper.findByTaskSupplierId(taskSupplier.getId())) {
          sheets.put(taskSupplier.getId() + "-" + sheet.getDimensionId(), sheet);
        }
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
    private int skippedCells;
    private int rowNumber;

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
              errors.add(new ImportReport.CellError(1, header, "表头里的指标编码不在该任务的模板版本里"));
            }
          });
    }

    @Override
    public void invoke(Map<Integer, String> row, AnalysisContext analysisContext) {
      rowNumber = analysisContext.readRowHolder().getRowIndex() + 1;
      String supplierCode = row.get(0);
      if (supplierCode == null || supplierCode.isBlank()) {
        errors.add(new ImportReport.CellError(rowNumber, SUPPLIER_CODE_HEADER, "供应商编码为空"));
        return;
      }
      Long supplierId = context.supplierIdByCode().get(supplierCode.trim());
      if (supplierId == null) {
        errors.add(new ImportReport.CellError(rowNumber, SUPPLIER_CODE_HEADER, "该供应商不在本任务内"));
        return;
      }
      TaskSupplier taskSupplier = context.taskSupplierBySupplierId().get(supplierId);
      if (taskSupplier == null) {
        errors.add(new ImportReport.CellError(rowNumber, SUPPLIER_CODE_HEADER, "该供应商还没有评估单"));
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
              errors.add(new ImportReport.CellError(rowNumber, header, "该指标对应的评分单不存在"));
              return;
            }
            if (sheet.getAssigneeId() == null || sheet.getAssigneeId() != context.operatorId()) {
              // 04-096：导入是**任务级**的（模板含全部列），但写入只能落在**指派给自己**的维度上。
              // 这里报错而不是像"待指派/已提交"那样静默跳过——"填了别人的格子"多半是拿错了表，
              // 咽下去就变成静默失效（CLAUDE 默认约定第 1 条）。
              errors.add(new ImportReport.CellError(rowNumber, header, "该指标不在指派给你的评分单里"));
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
              errors.add(new ImportReport.CellError(rowNumber, header, "不是合法数字：" + raw));
              return;
            }
            try {
              // 与打分页同一个策略：边界与精度只写一份（01 §6.6）
              ScoreStrategies.of(indicator).toHundred(value, indicator);
            } catch (IllegalArgumentException ex) {
              errors.add(new ImportReport.CellError(rowNumber, header, ex.getMessage()));
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
