package com.spe.context.evaluation.internal.web;

import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.evaluation.internal.approval.ApprovalService;
import com.spe.context.evaluation.internal.web.dto.ApproveRequest;
import com.spe.context.evaluation.internal.web.dto.RejectRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批动作（02 §4 / 15 §E5、§E8）。
 *
 * <p><b>两层权限</b>：类级只给 ADMIN/MANAGER 入口；真正的授权在 Service——当前节点不是你就 4003
 * （01 §3 经 2026-10-25 裁决：审批人只限 MANAGER/ADMIN，节点决定授权）。
 */
@RestController
@RequestMapping("/api/task-suppliers")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
@RequiredArgsConstructor
public class TaskSupplierApprovalController {

  private final ApprovalService approvalService;

  @PostMapping("/{id}/approve")
  public Result<Void> approve(
      @PathVariable long id,
      @Valid @RequestBody ApproveRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    approvalService.approve(id, operator.uid(), request.version());
    return Result.ok();
  }

  @PostMapping("/{id}/reject")
  public Result<Void> reject(
      @PathVariable long id,
      @Valid @RequestBody RejectRequest request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    approvalService.reject(id, operator.uid(), request.comment(), request.version());
    return Result.ok();
  }
}
