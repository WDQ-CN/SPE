package com.spe.context.alert.internal.web;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.alert.internal.CorrectiveAction;
import com.spe.context.alert.internal.CorrectiveActionDetailView;
import com.spe.context.alert.internal.CorrectiveActionService;
import com.spe.context.alert.internal.CorrectiveActionSummaryView;
import com.spe.context.alert.internal.web.dto.CancelCorrectiveActionRequest;
import com.spe.context.alert.internal.web.dto.StartCorrectiveActionRequest;
import com.spe.context.alert.internal.web.dto.SubmitCorrectiveActionRequest;
import com.spe.context.alert.internal.web.dto.UpdateCorrectiveActionRequest;
import com.spe.context.alert.internal.web.dto.VerifyCorrectiveActionRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 整改任务接口（W3；29 §3.5）。
 *
 * <p><b>两层权限</b>：类级只放 ADMIN/MANAGER/EVALUATOR 进入；EVALUATOR 是否只能看/动本人任务
 * 由 Service 的第二层过滤判定（越权 4001）。验证与取消另用方法级注解收窄到 ADMIN/MANAGER。
 *
 * <p>写接口统一返回最新详情：调用方下一步的 `version` 直接取响应，不必再发一次读请求。
 */
@RestController
@RequestMapping("/api/corrective-actions")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER','EVALUATOR')")
@RequiredArgsConstructor
public class CorrectiveActionController {

  private final CorrectiveActionService correctiveActionService;

  @GetMapping
  public Result<PageResult<CorrectiveActionSummaryView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Long supplierId,
      @RequestParam(required = false) Long ownerId,
      @RequestParam(required = false) Boolean overdue,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    return Result.ok(
        correctiveActionService.page(
            PageQuery.of(page, size),
            status,
            supplierId,
            ownerId,
            overdue,
            operator.uid(),
            isManager(authentication)));
  }

  @GetMapping("/{id}")
  public Result<CorrectiveActionDetailView> detail(
      @PathVariable long id,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    return Result.ok(
        correctiveActionService.detail(id, operator.uid(), isManager(authentication)));
  }

  @PutMapping("/{id}")
  public Result<CorrectiveActionDetailView> update(
      @PathVariable long id,
      @Valid @RequestBody UpdateCorrectiveActionRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    boolean manager = isManager(authentication);
    correctiveActionService.update(
        id,
        operator.uid(),
        manager,
        request.ownerId(),
        request.dueDate(),
        request.measure(),
        request.expectedResult(),
        request.version());
    return Result.ok(correctiveActionService.detail(id, operator.uid(), manager));
  }

  @PostMapping("/{id}/start")
  public Result<CorrectiveActionDetailView> start(
      @PathVariable long id,
      @Valid @RequestBody StartCorrectiveActionRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    boolean manager = isManager(authentication);
    correctiveActionService.start(id, operator.uid(), manager, request.version());
    return Result.ok(correctiveActionService.detail(id, operator.uid(), manager));
  }

  @PostMapping("/{id}/submit")
  public Result<CorrectiveActionDetailView> submit(
      @PathVariable long id,
      @Valid @RequestBody SubmitCorrectiveActionRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    boolean manager = isManager(authentication);
    correctiveActionService.submit(
        id, operator.uid(), manager, request.comment(), request.version());
    return Result.ok(correctiveActionService.detail(id, operator.uid(), manager));
  }

  @PostMapping("/{id}/verify")
  @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
  public Result<CorrectiveActionDetailView> verify(
      @PathVariable long id,
      @Valid @RequestBody VerifyCorrectiveActionRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    correctiveActionService.verify(
        id,
        operator.uid(),
        true,
        request.pass(),
        request.comment(),
        request.version());
    return Result.ok(correctiveActionService.detail(id, operator.uid(), true));
  }

  @PostMapping("/{id}/cancel")
  @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
  public Result<CorrectiveActionDetailView> cancel(
      @PathVariable long id,
      @Valid @RequestBody CancelCorrectiveActionRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    correctiveActionService.cancel(
        id, operator.uid(), true, request.reason(), request.version());
    return Result.ok(correctiveActionService.detail(id, operator.uid(), true));
  }

  private static boolean isManager(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(authority -> "ROLE_ADMIN".equals(authority) || "ROLE_MANAGER".equals(authority));
  }
}
