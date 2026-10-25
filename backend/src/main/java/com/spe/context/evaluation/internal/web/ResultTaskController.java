package com.spe.context.evaluation.internal.web;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.evaluation.api.EvalTaskStatus;
import com.spe.context.evaluation.internal.result.ResultReportService;
import com.spe.context.evaluation.internal.result.ResultTaskSummary;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结果报表入口（01 R2 / 02 §4）：只列**已有已生效评估单**的任务。
 *
 * <p>为什么不复用 `/api/tasks`：那个端点只对 ADMIN/MANAGER 开放（01 §7），而结果报表对 VIEWER 也可读
 * ——复用会让 VIEWER 在入口页拿到 403（e2e 实测，2026-10-24）。本端点只暴露结果所需的最小任务投影。
 */
@RestController
@RequestMapping("/api/results")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','VIEWER')")
@Validated
@RequiredArgsConstructor
public class ResultTaskController {

  private final ResultReportService resultReportService;

  @GetMapping("/tasks")
  public Result<PageResult<ResultTaskSummary>> tasks(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) EvalTaskStatus status,
      @RequestParam(required = false)
          @Size(max = 100, message = "关键字长度不得超过 100")
          String keyword) {
    return Result.ok(resultReportService.resultTasks(PageQuery.of(page, size), status, keyword));
  }
}
