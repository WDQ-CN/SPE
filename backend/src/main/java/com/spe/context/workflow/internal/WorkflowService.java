package com.spe.context.workflow.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 通用串行工作流（docs/erp/39 §2）：角色审批、SoD、版本化定义。 */
@Service
@RequiredArgsConstructor
public class WorkflowService {

  private final WorkflowDefinitionMapper definitionMapper;
  private final WorkflowNodeMapper nodeMapper;
  private final WorkflowInstanceMapper instanceMapper;
  private final WorkflowTaskMapper taskMapper;
  private final AuditFacade auditFacade;

  @Transactional
  public WorkflowDefinitionView createDefinition(
      long operatorId,
      String code,
      String name,
      String docType,
      String versionNo,
      BigDecimal minAmount,
      BigDecimal maxAmount,
      List<NodeCommand> nodes) {
    if (code == null || code.isBlank() || name == null || name.isBlank() || docType == null || docType.isBlank()) {
      throw new BizException(ErrorCode.WORKFLOW_DEFINITION_STATUS_INVALID, "编码/名称/单据类型不能为空");
    }
    if (nodes == null || nodes.isEmpty()) {
      throw new BizException(ErrorCode.WORKFLOW_DEFINITION_STATUS_INVALID, "至少需要一个审批节点");
    }
    BigDecimal min = minAmount == null ? BigDecimal.ZERO : minAmount;
    if (maxAmount != null && maxAmount.compareTo(min) < 0) {
      throw new BizException(ErrorCode.WORKFLOW_DEFINITION_STATUS_INVALID, "金额上限不能小于下限");
    }
    WorkflowDefinition definition = new WorkflowDefinition();
    definition.setCode(code.trim().toUpperCase());
    definition.setName(name.trim());
    definition.setDocType(docType.trim().toUpperCase());
    definition.setVersionNo(
        versionNo == null || versionNo.isBlank() ? "V1" : versionNo.trim().toUpperCase());
    definition.setConditionMinAmount(min);
    definition.setConditionMaxAmount(maxAmount);
    definition.setStatus(WorkflowDefinitionStatus.DRAFT);
    definition.setCreatedBy(operatorId);
    definitionMapper.insert(definition);
    int seq = 1;
    for (NodeCommand command : nodes) {
      if (command.name() == null || command.name().isBlank()) {
        throw new BizException(ErrorCode.WORKFLOW_DEFINITION_STATUS_INVALID, "节点名称不能为空");
      }
      WorkflowNode node = new WorkflowNode();
      node.setDefinitionId(definition.getId());
      node.setSeqNo(seq++);
      node.setName(command.name().trim());
      node.setApproverRole(WorkflowSupport.role(command.approverRole()));
      nodeMapper.insert(node);
    }
    auditFacade.record(
        AuditAction.WORKFLOW_DEFINITION_CREATE,
        AuditTargetType.WORKFLOW_DEFINITION,
        definition.getId(),
        Map.of("code", definition.getCode(), "docType", definition.getDocType(), "nodes", nodes.size()));
    return definitionDetail(definition.getId());
  }

  @Transactional
  public WorkflowDefinitionView activateDefinition(long id) {
    WorkflowDefinition definition =
        definitionMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.WORKFLOW_DEFINITION_NOT_FOUND));
    if (definitionMapper.markActive(id, LocalDateTime.now(ZoneOffset.UTC)) == 0) {
      throw new BizException(ErrorCode.WORKFLOW_DEFINITION_STATUS_INVALID);
    }
    definitionMapper.deactivateOthers(definition.getDocType(), id);
    auditFacade.record(
        AuditAction.WORKFLOW_DEFINITION_ACTIVATE,
        AuditTargetType.WORKFLOW_DEFINITION,
        id,
        Map.of("code", definition.getCode(), "version", definition.getVersionNo()));
    return definitionDetail(id);
  }

  @Transactional
  public WorkflowInstanceView start(
      long operatorId, long companyId, String docType, long docId, BigDecimal amount) {
    String normalizedDocType = docType == null ? null : docType.trim().toUpperCase();
    BigDecimal normalizedAmount = amount == null ? BigDecimal.ZERO : amount;
    if (instanceMapper.countRunningByDoc(normalizedDocType, docId) > 0) {
      throw new BizException(ErrorCode.WORKFLOW_TASK_STATUS_INVALID, "该单据已有进行中的审批");
    }
    WorkflowDefinition definition =
        definitionMapper
            .findMatching(normalizedDocType, normalizedAmount)
            .orElseThrow(() -> new BizException(ErrorCode.WORKFLOW_DEFINITION_MISSING));
    List<WorkflowNode> nodes = nodeMapper.findByDefinitionId(definition.getId());
    if (nodes.isEmpty()) {
      throw new BizException(ErrorCode.WORKFLOW_DEFINITION_MISSING);
    }
    WorkflowInstance instance = new WorkflowInstance();
    instance.setInstanceNo(WorkflowSupport.instanceNo());
    instance.setDefinitionId(definition.getId());
    instance.setDocType(normalizedDocType);
    instance.setDocId(docId);
    instance.setCompanyId(companyId);
    instance.setAmount(normalizedAmount);
    instance.setStatus(WorkflowInstanceStatus.RUNNING);
    instance.setCurrentSeq(nodes.get(0).getSeqNo());
    instance.setStartedBy(operatorId);
    instance.setVersion(0);
    instanceMapper.insert(instance);
    createTask(instance.getId(), nodes.get(0));
    auditFacade.record(
        AuditAction.WORKFLOW_INSTANCE_START,
        AuditTargetType.WORKFLOW_INSTANCE,
        instance.getId(),
        Map.of("instanceNo", instance.getInstanceNo(), "docType", normalizedDocType, "docId", docId));
    return instanceDetail(instance.getId());
  }

  @Transactional
  public WorkflowInstanceView approve(
      long taskId, long operatorId, Set<String> authorities, String opinion) {
    WorkflowTask task =
        taskMapper
            .lockById(taskId)
            .orElseThrow(() -> new BizException(ErrorCode.WORKFLOW_TASK_NOT_FOUND));
    if (task.getStatus() != WorkflowTaskStatus.PENDING) {
      throw new BizException(ErrorCode.WORKFLOW_TASK_STATUS_INVALID);
    }
    WorkflowInstance instance =
        instanceMapper
            .lockById(task.getInstanceId())
            .orElseThrow(() -> new BizException(ErrorCode.WORKFLOW_INSTANCE_NOT_FOUND));
    if (instance.getStatus() != WorkflowInstanceStatus.RUNNING
        || task.getSeqNo() != instance.getCurrentSeq()) {
      throw new BizException(ErrorCode.WORKFLOW_TASK_STATUS_INVALID);
    }
    requireRole(task, authorities);
    if (instance.getStartedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION);
    }
    // 同一人不能连续通过同一实例的多个节点（双签/多级审批必须不同人）。
    if (taskMapper.countApprovedByInstanceAndActor(instance.getId(), operatorId) > 0) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "同一审批人不能连续审批多个节点");
    }
    if (taskMapper.markApproved(taskId, operatorId, LocalDateTime.now(ZoneOffset.UTC), opinion) == 0) {
      throw new BizException(ErrorCode.WORKFLOW_TASK_STATUS_INVALID);
    }
    WorkflowNode next =
        nodeMapper
            .findByDefinitionAndSeq(instance.getDefinitionId(), task.getSeqNo() + 1)
            .orElse(null);
    if (next == null) {
      if (instanceMapper.complete(
              instance.getId(), WorkflowInstanceStatus.APPROVED, LocalDateTime.now(ZoneOffset.UTC))
          == 0) {
        throw new BizException(ErrorCode.WORKFLOW_TASK_STATUS_INVALID);
      }
    } else {
      if (instanceMapper.advance(instance.getId(), next.getSeqNo()) == 0) {
        throw new BizException(ErrorCode.WORKFLOW_TASK_STATUS_INVALID);
      }
      createTask(instance.getId(), next);
    }
    auditFacade.record(
        AuditAction.WORKFLOW_TASK_APPROVE,
        AuditTargetType.WORKFLOW_TASK,
        taskId,
        Map.of("instanceId", instance.getId(), "seq", task.getSeqNo()));
    return instanceDetail(instance.getId());
  }

  @Transactional
  public WorkflowInstanceView reject(
      long taskId, long operatorId, Set<String> authorities, String opinion) {
    if (opinion == null || opinion.isBlank()) {
      throw new BizException(ErrorCode.BAD_REQUEST, "驳回意见不能为空");
    }
    WorkflowTask task =
        taskMapper
            .lockById(taskId)
            .orElseThrow(() -> new BizException(ErrorCode.WORKFLOW_TASK_NOT_FOUND));
    if (task.getStatus() != WorkflowTaskStatus.PENDING) {
      throw new BizException(ErrorCode.WORKFLOW_TASK_STATUS_INVALID);
    }
    WorkflowInstance instance =
        instanceMapper
            .lockById(task.getInstanceId())
            .orElseThrow(() -> new BizException(ErrorCode.WORKFLOW_INSTANCE_NOT_FOUND));
    if (instance.getStatus() != WorkflowInstanceStatus.RUNNING
        || task.getSeqNo() != instance.getCurrentSeq()) {
      throw new BizException(ErrorCode.WORKFLOW_TASK_STATUS_INVALID);
    }
    requireRole(task, authorities);
    if (instance.getStartedBy() == operatorId) {
      throw new BizException(ErrorCode.PROCUREMENT_SOD_VIOLATION, "发起人不能驳回自己的审批");
    }
    if (taskMapper.markRejected(taskId, operatorId, LocalDateTime.now(ZoneOffset.UTC), opinion)
        == 0) {
      throw new BizException(ErrorCode.WORKFLOW_TASK_STATUS_INVALID);
    }
    if (instanceMapper.complete(
            instance.getId(), WorkflowInstanceStatus.REJECTED, LocalDateTime.now(ZoneOffset.UTC))
        == 0) {
      throw new BizException(ErrorCode.WORKFLOW_TASK_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.WORKFLOW_TASK_REJECT,
        AuditTargetType.WORKFLOW_TASK,
        taskId,
        Map.of("instanceId", instance.getId(), "opinion", opinion));
    return instanceDetail(instance.getId());
  }

  @Transactional(readOnly = true)
  public PageResult<WorkflowDefinition> pageDefinitions(int page, int size) {
    PageQuery query = PageQuery.of(page, size);
    return PageResult.of(
        definitionMapper.findPage(query.offset(), query.size()),
        definitionMapper.countAll(),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public WorkflowDefinitionView definitionDetail(long id) {
    WorkflowDefinition definition =
        definitionMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.WORKFLOW_DEFINITION_NOT_FOUND));
    return new WorkflowDefinitionView(
        definition.getId(),
        definition.getCode(),
        definition.getName(),
        definition.getDocType(),
        definition.getVersionNo(),
        definition.getConditionMinAmount(),
        definition.getConditionMaxAmount(),
        definition.getStatus(),
        definition.getCreatedAt(),
        definition.getActivatedAt(),
        nodeMapper.findByDefinitionId(id).stream()
            .map(
                node ->
                    new WorkflowDefinitionView.Node(
                        node.getId(), node.getSeqNo(), node.getName(), node.getApproverRole()))
            .toList());
  }

  @Transactional(readOnly = true)
  public PageResult<WorkflowInstance> pageInstances(
      int page, int size, Long companyId, String status, String docType) {
    PageQuery query = PageQuery.of(page, size);
    WorkflowInstanceStatus parsed =
        status == null || status.isBlank()
            ? null
            : parseInstanceStatus(status);
    String normalizedDocType =
        docType == null || docType.isBlank() ? null : docType.trim().toUpperCase();
    return PageResult.of(
        instanceMapper.findPage(
            companyId, parsed, normalizedDocType, query.offset(), query.size()),
        instanceMapper.countBy(companyId, parsed, normalizedDocType),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public WorkflowInstanceView instanceDetail(long id) {
    WorkflowInstance instance =
        instanceMapper
            .findById(id)
            .orElseThrow(() -> new BizException(ErrorCode.WORKFLOW_INSTANCE_NOT_FOUND));
    return new WorkflowInstanceView(
        instance.getId(),
        instance.getInstanceNo(),
        instance.getDefinitionId(),
        instance.getDocType(),
        instance.getDocId(),
        instance.getCompanyId(),
        instance.getAmount(),
        instance.getStatus(),
        instance.getCurrentSeq(),
        instance.getStartedBy(),
        instance.getStartedAt(),
        instance.getCompletedAt(),
        taskMapper.findByInstanceId(id).stream()
            .map(
                task ->
                    new WorkflowInstanceView.Task(
                        task.getId(),
                        task.getSeqNo(),
                        task.getApproverRole(),
                        task.getStatus(),
                        task.getActedBy(),
                        task.getActedAt(),
                        task.getOpinion()))
            .toList());
  }

  @Transactional(readOnly = true)
  public List<WorkflowInstanceView.Task> pendingTasks(
      Set<String> authorities, String role, int size) {
    String normalizedRole = role == null || role.isBlank() ? null : WorkflowSupport.role(role);
    List<String> allowedRoles =
        authorities == null
            ? List.of()
            : authorities.stream()
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .toList();
    if (normalizedRole != null && !allowedRoles.contains(normalizedRole)) {
      throw new BizException(ErrorCode.FORBIDDEN, "不能查看其他角色的待办");
    }
    List<String> roles = normalizedRole == null ? allowedRoles : List.of(normalizedRole);
    if (roles.isEmpty()) {
      return List.of();
    }
    return taskMapper.findPendingByRoles(roles, Math.max(1, Math.min(size, 200))).stream()
        .map(
            task ->
                new WorkflowInstanceView.Task(
                    task.getId(),
                    task.getSeqNo(),
                    task.getApproverRole(),
                    task.getStatus(),
                    task.getActedBy(),
                    task.getActedAt(),
                    task.getOpinion()))
        .toList();
  }

  private void createTask(long instanceId, WorkflowNode node) {
    WorkflowTask task = new WorkflowTask();
    task.setInstanceId(instanceId);
    task.setSeqNo(node.getSeqNo());
    task.setApproverRole(node.getApproverRole());
    task.setStatus(WorkflowTaskStatus.PENDING);
    taskMapper.insert(task);
  }

  private static void requireRole(WorkflowTask task, Set<String> authorities) {
    if (authorities == null
        || !authorities.contains("ROLE_" + task.getApproverRole())) {
      throw new BizException(
          ErrorCode.WORKFLOW_APPROVER_ROLE_INVALID,
          "需要角色 " + task.getApproverRole());
    }
  }

  private static WorkflowInstanceStatus parseInstanceStatus(String status) {
    try {
      return WorkflowInstanceStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "实例状态不合法：" + status);
    }
  }

  /** 节点命令。 */
  public record NodeCommand(String name, String approverRole) {}
}
