package com.spe.context.workflow.internal.web;

import com.spe.common.page.PageResult;
import com.spe.common.security.AuthenticatedOperator;
import com.spe.common.web.Result;
import com.spe.context.workflow.internal.WorkflowDefinition;
import com.spe.context.workflow.internal.WorkflowDefinitionView;
import com.spe.context.workflow.internal.WorkflowInstance;
import com.spe.context.workflow.internal.WorkflowInstanceView;
import com.spe.context.workflow.internal.WorkflowService;
import com.spe.context.workflow.internal.web.dto.WorkflowRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 通用工作流（docs/erp/39）。 */
@RestController
@RequestMapping("/api/workflows")
@PreAuthorize("isAuthenticated()")
@Validated
@RequiredArgsConstructor
public class WorkflowController {

  private final WorkflowService workflowService;

  @GetMapping("/definitions")
  @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
  public Result<PageResult<WorkflowDefinition>> definitions(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
    return Result.ok(workflowService.pageDefinitions(page, size));
  }

  @PostMapping("/definitions")
  @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
  public Result<WorkflowDefinitionView> createDefinition(
      @Valid @RequestBody WorkflowRequests.CreateDefinition request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        workflowService.createDefinition(
            operator.uid(),
            request.code(),
            request.name(),
            request.docType(),
            request.versionNo(),
            request.conditionMinAmount(),
            request.conditionMaxAmount(),
            request.nodes().stream()
                .map(node -> new WorkflowService.NodeCommand(node.name(), node.approverRole()))
                .toList()));
  }

  @GetMapping("/definitions/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
  public Result<WorkflowDefinitionView> definitionDetail(@PathVariable long id) {
    return Result.ok(workflowService.definitionDetail(id));
  }

  @PostMapping("/definitions/{id}/activate")
  @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
  public Result<WorkflowDefinitionView> activateDefinition(@PathVariable long id) {
    return Result.ok(workflowService.activateDefinition(id));
  }

  @GetMapping("/instances")
  public Result<PageResult<WorkflowInstance>> instances(
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
      @RequestParam(required = false) Long companyId,
      @RequestParam(required = false) @Size(max = 16) String status,
      @RequestParam(required = false) @Size(max = 64) String docType) {
    return Result.ok(workflowService.pageInstances(page, size, companyId, status, docType));
  }

  @PostMapping("/instances")
  public Result<WorkflowInstanceView> startInstance(
      @Valid @RequestBody WorkflowRequests.StartInstance request,
      @AuthenticationPrincipal AuthenticatedOperator operator) {
    return Result.ok(
        workflowService.start(
            operator.uid(),
            request.companyId(),
            request.docType(),
            request.docId(),
            request.amount()));
  }

  @GetMapping("/instances/{id}")
  public Result<WorkflowInstanceView> instanceDetail(@PathVariable long id) {
    return Result.ok(workflowService.instanceDetail(id));
  }

  @GetMapping("/tasks")
  public Result<List<WorkflowInstanceView.Task>> pendingTasks(
      @RequestParam(required = false) @Size(max = 32) String role,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
      Authentication authentication) {
    return Result.ok(workflowService.pendingTasks(authorities(authentication), role, size));
  }

  @PostMapping("/tasks/{id}/approve")
  public Result<WorkflowInstanceView> approve(
      @PathVariable long id,
      @Valid @RequestBody WorkflowRequests.Act request,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    return Result.ok(
        workflowService.approve(
            id, operator.uid(), authorities(authentication), request.opinion()));
  }

  @PostMapping("/tasks/{id}/reject")
  public Result<WorkflowInstanceView> reject(
      @PathVariable long id,
      @Valid @RequestBody WorkflowRequests.Act request,
      @AuthenticationPrincipal AuthenticatedOperator operator,
      Authentication authentication) {
    return Result.ok(
        workflowService.reject(
            id, operator.uid(), authorities(authentication), request.opinion()));
  }

  private static Set<String> authorities(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .collect(Collectors.toSet());
  }
}
