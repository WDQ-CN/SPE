package com.spe.context.governance.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.governance.internal.SodConflictView;
import com.spe.context.governance.internal.SodException;
import com.spe.context.governance.internal.SodRule;
import com.spe.context.governance.internal.SodService;
import com.spe.context.governance.internal.web.dto.GovernanceRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
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

/** SoD 规则与例外（docs/erp/41）。 */
@RestController
@RequestMapping("/api/governance/sod")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
@Validated
@RequiredArgsConstructor
public class SodController {

  private final SodService sodService;

  @GetMapping("/rules")
  public Result<List<SodRule>> rules() {
    return Result.ok(sodService.listRules());
  }

  @PostMapping("/rules")
  public Result<SodRule> createRule(
      @Valid @RequestBody GovernanceRequests.CreateSodRule request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        sodService.createRule(
            operator.uid(),
            request.code(),
            request.roleA(),
            request.roleB(),
            request.description()));
  }

  @PostMapping("/check")
  public Result<List<SodConflictView>> check(
      @Valid @RequestBody GovernanceRequests.CheckSod request) {
    return Result.ok(sodService.check(request.roles(), request.userId(), request.date()));
  }

  @GetMapping("/exceptions")
  public Result<PageResult<SodException>> exceptions(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
    return Result.ok(sodService.pageExceptions(page, size));
  }

  @PostMapping("/exceptions")
  public Result<SodException> requestException(
      @Valid @RequestBody GovernanceRequests.RequestSodException request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        sodService.requestException(
            operator.uid(),
            request.userId(),
            request.ruleId(),
            request.reason(),
            request.validFrom(),
            request.validTo()));
  }

  @PostMapping("/exceptions/{id}/approve")
  public Result<SodException> approve(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(sodService.review(id, operator.uid(), true));
  }

  @PostMapping("/exceptions/{id}/reject")
  public Result<SodException> reject(
      @PathVariable long id, @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(sodService.review(id, operator.uid(), false));
  }
}
