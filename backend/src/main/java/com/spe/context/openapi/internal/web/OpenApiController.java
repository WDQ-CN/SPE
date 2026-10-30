package com.spe.context.openapi.internal.web;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.openapi.internal.OpenApiScoreRequest;
import com.spe.context.openapi.internal.OpenApiScoreResponse;
import com.spe.context.openapi.internal.OpenApiService;
import com.spe.context.openapi.internal.OpenApiTaskIndicatorsResponse;
import com.spe.context.openapi.internal.OpenApiTaskResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API（M6 / 01 I1~I2）：Token 鉴权，查询进行中任务/指标，按任务+供应商+指标幂等上报。
 *
 * <p>Controller 只依赖自身上下文（R-E）；跨上下文读取/写入都在 {@link OpenApiService} 经
 * evaluation.api 门面完成。
 */
@RestController
@RequestMapping("/open/v1")
@PreAuthorize("hasRole('API_TOKEN')")
@RequiredArgsConstructor
public class OpenApiController {

  private final OpenApiService openApiService;

  @GetMapping("/tasks/active")
  public Result<PageResult<OpenApiTaskResponse>> activeTasks(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
    return Result.ok(openApiService.activeTasks(PageQuery.of(page, size)));
  }

  @GetMapping("/tasks/{id}/indicators")
  public Result<OpenApiTaskIndicatorsResponse> taskIndicators(@PathVariable long id) {
    return Result.ok(openApiService.taskIndicators(id));
  }

  @PutMapping("/scores")
  public Result<OpenApiScoreResponse> upsertScore(
      @Valid @RequestBody OpenApiScoreRequest request) {
    return Result.ok(openApiService.upsert(request));
  }
}
