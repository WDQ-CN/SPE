package com.spe.context.evaluation.internal.result;

import com.alibaba.excel.EasyExcel;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.evaluation.internal.task.EvalTask;
import com.spe.context.evaluation.internal.task.EvalTaskMapper;
import com.spe.context.template.api.DimensionSnapshot;
import com.spe.context.template.api.TemplateSnapshot;
import com.spe.context.template.api.TemplateSnapshotFacade;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评估结果导出（01 R4；M5 W5，22 §2 D7）。
 *
 * <p>取数复用 {@link ResultReportService#results(long)}（与结果页同源、只含 EFFECTIVE）；
 * 表头固定四列 + 每个维度两列（分/等级）。**公式注入防护**：文本单元格以 `= + - @ TAB CR` 开头时
 * 前置 `'`（Excel 会当文本）；供应商名/编码、等级、维度名都过这一层。
 *
 * <p>文件名 `结果-<任务名>-<yyyyMMdd>.xlsx`；控制器负责 RFC 5987 编码与响应头，避免中文乱码。
 */
@Service
@RequiredArgsConstructor
public class ResultExportService {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

  private final ResultReportService resultReportService;
  private final EvalTaskMapper taskMapper;
  private final TemplateSnapshotFacade templateSnapshotFacade;

  /** 导出模型：先装配、后写流（控制器要先设置响应头，再写内容）。 */
  public record ExportFile(String filename, List<List<String>> head, List<List<Object>> data) {}

  /** 装配导出内容（不写流）；含生效结果 + 表头 + 防注入。 */
  @Transactional(readOnly = true)
  public ExportFile build(long taskId) {
    EvalTask task =
        taskMapper
            .findById(taskId)
            .orElseThrow(() -> new BizException(ErrorCode.TASK_NOT_FOUND));
    TaskResults results = resultReportService.results(taskId);

    // 表头维度：优选用结果行里的（与快照一致）；无生效结果时回落到模板快照，保证"只有表头"
    Set<String> dimensionNames = new LinkedHashSet<>();
    for (TaskResults.Row row : results.rows()) {
      row.dimensions().forEach(dimension -> dimensionNames.add(dimension.dimensionName()));
    }
    if (dimensionNames.isEmpty()) {
      TemplateSnapshot snapshot = templateSnapshotFacade.requirePublished(task.getTemplateVersionId());
      for (DimensionSnapshot dimension : snapshot.dimensions()) {
        dimensionNames.add(dimension.name());
      }
    }

    List<List<String>> head = new ArrayList<>();
    head.add(List.of("供应商编码"));
    head.add(List.of("供应商名称"));
    head.add(List.of("总分"));
    head.add(List.of("总等级"));
    for (String name : dimensionNames) {
      head.add(List.of(safe(name) + "-分"));
      head.add(List.of(safe(name) + "-等级"));
    }

    List<List<Object>> data = new ArrayList<>(results.rows().size());
    for (TaskResults.Row row : results.rows()) {
      List<Object> line = new ArrayList<>(4 + dimensionNames.size() * 2);
      line.add(safe(row.supplierCode()));
      line.add(safe(row.supplierName()));
      line.add(row.totalScore());
      line.add(safe(row.totalGrade()));
      for (String name : dimensionNames) {
        TaskResults.DimensionScore dimension =
            row.dimensions().stream()
                .filter(item -> item.dimensionName().equals(name))
                .findFirst()
                .orElse(null);
        line.add(dimension == null ? null : dimension.score());
        line.add(dimension == null ? null : safe(dimension.grade()));
      }
      data.add(line);
    }

    return new ExportFile(
        "结果-" + stripFileName(task.getName()) + "-" + LocalDate.now().format(DATE) + ".xlsx",
        head,
        data);
  }

  /** 把装配结果写成 xlsx（不在事务里；流由调用方关闭/flush）。 */
  public void write(ExportFile file, OutputStream out) throws IOException {
    EasyExcel.write(out).head(file.head()).sheet("评估结果").doWrite(file.data());
  }
  /** 文本单元格防公式注入（D7）：只处理会触发 Excel 解析的前缀字符。 */  static String safe(String value) {
    if (value == null) {
      return "";
    }
    if (value.isEmpty()) {
      return value;
    }
    char first = value.charAt(0);
    if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
      return "'" + value;
    }
    return value;
  }

  /** 文件名里不允许引号/控制字符（响应头注入面）；其余中文保留，交给 RFC 5987 编码。 */
  static String stripFileName(String name) {
    return name == null ? "任务" : name.replaceAll("[\"\\r\\n\\\\/:*?<>|]", "_");
  }
}
