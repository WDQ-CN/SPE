package com.spe.context.evaluation.internal.web;

import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.evaluation.internal.result.ResultReportService;
import com.spe.context.evaluation.internal.result.TaskSupplierReport;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评估报告页数据（01 R3 / 15 §E8/E15）。
 *
 * <p>类级只要登录：01 §7 允许评估人看**本人参与过**的报告。这里把"是否受限"作为 flag 交给 Service，
 * 第二层判据（按评估单的参与关系）在 Service 里查库，不依赖前端或角色字符串的拼写。
 */
@RestController
@RequestMapping("/api/task-suppliers")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class TaskSupplierReportController {

  private static final Set<String> FULL_VIEW_ROLES =
      Set.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_VIEWER");

  private final ResultReportService resultReportService;

  @GetMapping("/{id}/report")
  public Result<TaskSupplierReport> report(
      @PathVariable long id,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    boolean privileged =
        authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .anyMatch(FULL_VIEW_ROLES::contains);
    return Result.ok(resultReportService.report(id, operator.uid(), privileged));
  }
}
