package com.spe.context.evaluation.internal.web;

import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.evaluation.internal.scoring.ImportReport;
import com.spe.context.evaluation.internal.scoring.ScoreImportService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 任务级 Excel 导入（01 E3 / 13 §E17、§E18）。
 *
 * <p><b>为什么单独一个 Controller</b>：这两个端点的角色是 **EVALUATOR/MANAGER**，而 ` TaskController`
 * 的类级注解是 ADMIN/MANAGER。Spring Security 的类级与方法级注解是**同时生效**的（方法级不能放宽类级），
 * 所以只能拆类——这也让"谁能导入"这一个事实只出现在一处。
 *
 * <p>模板与导入都是**任务级**（行 = 供应商、列 = 指标编码）；`02 §4` 原有的两个 sheet 级端点在
 * 2026-09-26 的裁决里已删除（13 §E17）。
 */
@RestController
@RequestMapping("/api/tasks")
@PreAuthorize("hasAnyRole('MANAGER','EVALUATOR')")
@RequiredArgsConstructor
public class TaskImportController {

  private final ScoreImportService scoreImportService;

  /** 下载导入模板（xlsx 字节流）；**须参与该任务**（否则 4002，04-096）。 */
  @GetMapping("/{id}/import-template")
  public ResponseEntity<byte[]> template(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    byte[] body = scoreImportService.buildTemplate(id, operator.uid());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"task-" + id + "-import-template.xlsx\"")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(body);
  }

  /**
   * 上传并导入；错误按行列位置返回（有错则整批不写，2027）。
   *
   * <p>**须参与该任务**，且逐格只能落在**指派给自己**的评分单上（04-096）：模板是任务级的
   * （列 = 全部指标），所以"能碰这个任务"与"这一格归不归我"是两条独立的判据，都要判。
   */
  @PostMapping("/{id}/import")
  public Result<ImportReport> importScores(
      @PathVariable long id,
      @RequestParam("file") MultipartFile file,
      @AuthenticationPrincipal AuthenticatedOperator operator)
      throws IOException {
    return Result.ok(
        scoreImportService.importScores(
            id, file.getInputStream(), ScoreImportService.newBatchNo(), operator.uid()));
  }
}
