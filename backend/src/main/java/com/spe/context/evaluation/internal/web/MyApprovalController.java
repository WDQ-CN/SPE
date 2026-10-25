package com.spe.context.evaluation.internal.web;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.evaluation.internal.approval.ApprovalService;
import com.spe.context.evaluation.internal.approval.ApprovalTodoView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的审批待办（15 §E8 / 02 §4）：当前节点是我、状态在审批中的评估单。
 *
 * <p>角色只决定入口：`isAuthenticated()`（01 §3 的审批人限 MANAGER/ADMIN，见 {@code 21 §3.W1-1}）；
 * "这一单是不是我这一节点"由审批表中的数据决定，过滤写在 Service 的 SQL 里。
 */
@RestController
@RequestMapping("/api/my/approvals")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MyApprovalController {

  private final ApprovalService approvalService;

  @GetMapping
  public Result<PageResult<ApprovalTodoView>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(approvalService.listMyApprovals(operator.uid(), PageQuery.of(page, size)));
  }
}
