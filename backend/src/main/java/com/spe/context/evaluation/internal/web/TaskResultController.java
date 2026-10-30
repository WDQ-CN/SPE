package com.spe.context.evaluation.internal.web;

import com.spe.common.web.Result;
import com.spe.context.evaluation.internal.result.ResultExportService;
import com.spe.context.evaluation.internal.result.ResultReportService;
import com.spe.context.evaluation.internal.result.TaskResults;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 任务级结果列表（01 R2 / 15 §E8）：只含已生效的评估单；ADMIN/MANAGER/VIEWER。 */
@RestController
@RequestMapping("/api/tasks")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','VIEWER')")
@RequiredArgsConstructor
public class TaskResultController {

  private final ResultReportService resultReportService;
  private final ResultExportService resultExportService;

  @GetMapping("/{id}/results")
  public Result<TaskResults> results(@PathVariable long id) {
    return Result.ok(resultReportService.results(id));
  }

  /**
   * 结果导出（01 R4；M5 W5）：xlsx 直接写响应流，不整包缓冲。
   *
   * <p>先写响应头再写内容：取数/表头装配阶段的异常仍能走统一异常处理器（此时尚未提交响应）；
   * 文件名的中文用 RFC 5987（Spring 的 ContentDisposition 负责编码）。
   */
  @GetMapping("/{id}/results/export")
  public void export(@PathVariable long id, HttpServletResponse response) throws IOException {
    // 先装配、再设头、最后写体：头必须在响应提交前设置（否则被忽略/抛 IllegalState）
    var file = resultExportService.build(id);
    response.setContentType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION,
        ContentDisposition.attachment()
            .filename(file.filename(), StandardCharsets.UTF_8)
            .build()
            .toString());
    var out = response.getOutputStream();
    resultExportService.write(file, out);
    out.flush();
  }
}
