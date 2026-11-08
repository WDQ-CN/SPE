package com.spe.context.project.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.project.internal.ProjectTimesheet;
import com.spe.context.project.internal.TimesheetService;
import com.spe.context.project.internal.TimesheetView;
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

/** 项目工时（docs/erp/38）。 */
@RestController
@RequestMapping("/api/timesheets")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class TimesheetController {

  private final TimesheetService timesheetService;

  @GetMapping
  public Result<PageResult<ProjectTimesheet>> list(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) @Size(max = 16) String status) {
    return Result.ok(timesheetService.page(page, size, companyId, userId, status));
  }

  @PostMapping
  public Result<TimesheetView> create(
      @Valid @RequestBody ProjectRequests.CreateTimesheet request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        timesheetService.create(
            operator.uid(),
            request.companyId(),
            request.lines().stream()
                .map(
                    line ->
                        new TimesheetService.LineCommand(
                            line.projectId(), line.wbsId(), line.workDate(), line.hours()))
                .toList()));
  }

  @GetMapping("/{id}")
  public Result<TimesheetView> detail(@PathVariable long id) {
    return Result.ok(timesheetService.detail(id));
  }

  @PostMapping("/{id}/submit")
  public Result<TimesheetView> submit(@PathVariable long id) {
    return Result.ok(timesheetService.submit(id));
  }

  @PostMapping("/{id}/approve")
  public Result<TimesheetView> approve(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(timesheetService.approve(id, operator.uid()));
  }
}
