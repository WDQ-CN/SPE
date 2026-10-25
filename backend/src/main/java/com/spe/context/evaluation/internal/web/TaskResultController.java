package com.spe.context.evaluation.internal.web;

import com.spe.common.web.Result;
import com.spe.context.evaluation.internal.result.ResultReportService;
import com.spe.context.evaluation.internal.result.TaskResults;
import lombok.RequiredArgsConstructor;
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

  @GetMapping("/{id}/results")
  public Result<TaskResults> results(@PathVariable long id) {
    return Result.ok(resultReportService.results(id));
  }
}
