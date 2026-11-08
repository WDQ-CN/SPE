package com.spe.context.project.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.project.internal.Project;
import com.spe.context.project.internal.ProjectService;
import com.spe.context.project.internal.ProjectView;
import com.spe.context.project.internal.web.dto.ProjectRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 项目/WBS/预算（docs/erp/38）。 */
@RestController
@RequestMapping("/api/projects")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class ProjectController {

  private final ProjectService projectService;

  @GetMapping
  public Result<PageResult<Project>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(projectService.page(page, size, companyId, status));
  }

  @PostMapping
  public Result<ProjectView> create(
      @Valid @RequestBody ProjectRequests.CreateProject request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        projectService.create(
            operator.uid(),
            request.companyId(),
            request.customerId(),
            request.name(),
            request.currency(),
            request.defaultLaborRate(),
            request.startDate(),
            request.endDate(),
            request.managerId()));
  }

  @GetMapping("/{id}")
  public Result<ProjectView> detail(@PathVariable long id) {
    return Result.ok(projectService.detail(id));
  }

  @PostMapping("/{id}/approve")
  public Result<ProjectView> approve(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(projectService.approve(id, operator.uid()));
  }

  @PostMapping("/{id}/activate")
  public Result<ProjectView> activate(@PathVariable long id) {
    return Result.ok(projectService.activate(id));
  }

  @PostMapping("/{id}/close")
  public Result<ProjectView> close(@PathVariable long id) {
    return Result.ok(projectService.close(id));
  }

  @PostMapping("/{id}/wbs")
  public Result<ProjectView> addWbs(
      @PathVariable long id, @Valid @RequestBody ProjectRequests.AddWbs request) {
    return Result.ok(
        projectService.addWbs(
            id, request.parentId(), request.code(), request.name(), request.budget()));
  }

  @PostMapping("/{id}/budgets")
  public Result<ProjectView> createBudget(
      @PathVariable long id,
      @Valid @RequestBody ProjectRequests.CreateBudget request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        projectService.createBudget(
            operator.uid(),
            id,
            request.versionNo(),
            request.laborAmount(),
            request.purchaseAmount(),
            request.expenseAmount(),
            request.otherAmount()));
  }

  @PostMapping("/budgets/{budgetId}/approve")
  public Result<ProjectView> approveBudget(
      @PathVariable long budgetId, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(projectService.approveBudget(budgetId, operator.uid()));
  }
}
