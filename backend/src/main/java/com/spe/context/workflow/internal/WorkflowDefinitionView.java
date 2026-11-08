package com.spe.context.workflow.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 工作流定义投影。 */
public record WorkflowDefinitionView(
    long id,
    String code,
    String name,
    String docType,
    String versionNo,
    BigDecimal conditionMinAmount,
    BigDecimal conditionMaxAmount,
    WorkflowDefinitionStatus status,
    LocalDateTime createdAt,
    LocalDateTime activatedAt,
    List<Node> nodes) {

  public record Node(long id, int seqNo, String name, String approverRole) {}
}
