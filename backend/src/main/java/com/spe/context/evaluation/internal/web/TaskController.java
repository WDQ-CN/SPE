package com.spe.context.evaluation.internal.web;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.Result;
import com.spe.context.evaluation.api.EvalTaskStatus;
import com.spe.context.evaluation.internal.task.PublishResult;
import com.spe.context.evaluation.internal.task.TaskDetail;
import com.spe.context.evaluation.internal.task.TaskProgress;
import com.spe.context.evaluation.internal.task.TaskProgressService;
import com.spe.context.evaluation.internal.task.TaskService;
import com.spe.context.evaluation.internal.task.TaskSummary;
import com.spe.context.evaluation.internal.web.dto.CreateTaskRequest;
import com.spe.context.evaluation.internal.web.dto.PublishTaskRequest;
import com.spe.context.evaluation.internal.web.dto.UpdateTaskRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评估任务接口（02 §4 / 13 §E18）。
 *
 * <p><b>权限</b>锚定 01 §7：读是 ADMIN/MANAGER，写（建/改/发布）**只有 MANAGER**（ADMIN 只读）——
 * 类级注解是"默认拒绝"的落地（R-H 会卡），方法级再收紧写操作。越权由后端 4xxx 兜底并留
 * `ACCESS_DENIED`，不依赖前端隐藏入口。
 *
 * <p>Controller 只做参数校验、调 Service、包 `Result`；审计与事务都在 Service（06 §2.7）。
 */
@RestController
@RequestMapping("/api/tasks")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class TaskController {

  private final TaskService taskService;
  private final TaskProgressService taskProgressService;

  /** 列表：名称包含匹配 + 可选状态；分页走 `PageQuery`（单页上限单点定义）。 */
  @GetMapping
  public Result<PageResult<TaskSummary>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) EvalTaskStatus status,
      @RequestParam(required = false) @Size(max = 100, message = "关键字长度不得超过 100") String keyword) {
    return Result.ok(taskService.page(PageQuery.of(page, size), status, keyword));
  }

  /** 详情（13 §E18 的缺口端点）：任务 + 勾选供应商 + 审批链 + 评分单计数。 */
  @GetMapping("/{id}")
  public Result<TaskDetail> detail(@PathVariable long id) {
    return Result.ok(taskService.findDetail(id));
  }

  @PostMapping
  @PreAuthorize("hasRole('MANAGER')")
  public Result<Long> create(@Valid @RequestBody CreateTaskRequest request) {
    return Result.ok(
        taskService.createDraft(
            new com.spe.context.evaluation.internal.task.TaskDraftCommand(
                request.name(),
                request.year(),
                request.quarter(),
                request.templateId(),
                request.templateVersionId(),
                request.deadline(),
                request.supplierIds(),
                request.approverIds())));
  }

  /** 编辑草稿（缺口端点）：状态不对 → 2020，版本不对 → 3001。 */
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('MANAGER')")
  public Result<Void> update(@PathVariable long id, @Valid @RequestBody UpdateTaskRequest request) {
    taskService.updateDraft(
        id,
        request.version(),
        new com.spe.context.evaluation.internal.task.TaskDraftCommand(
            request.name(),
            request.year(),
            request.quarter(),
            request.templateId(),
            request.templateVersionId(),
            request.deadline(),
            request.supplierIds(),
            request.approverIds()));
    return Result.ok();
  }

  /** 发布（T1）：单事务生成评估单与评分单、自动指派、发事件。 */
  @PostMapping("/{id}/publish")
  @PreAuthorize("hasRole('MANAGER')")
  public Result<PublishResult> publish(
      @PathVariable long id, @Valid @RequestBody PublishTaskRequest request) {
    return Result.ok(taskService.publish(id, request.version()));
  }

  /** 任务下的评分单清单（缺口端点）：详情页的「指派调整」用它拿 sheetId / 指派对象 / 版本。 */
  @GetMapping("/{id}/sheets")
  public Result<java.util.List<com.spe.context.evaluation.internal.task.SheetBrief>> sheets(
      @PathVariable long id) {
    return Result.ok(taskService.findSheets(id));
  }

  /** 进度看板（01 J3）：每供应商一行，含试算得分（不完整时为 null）。 */
  @GetMapping("/{id}/progress")
  public Result<TaskProgress> progress(@PathVariable long id) {
    return Result.ok(taskProgressService.progress(id));
  }
}
