package com.spe.context.workflow.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 工作流实例投影。 */
public record WorkflowInstanceView(
    long id,
    String instanceNo,
    long definitionId,
    String docType,
    long docId,
    long companyId,
    BigDecimal amount,
    WorkflowInstanceStatus status,
    int currentSeq,
    long startedBy,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    List<Task> tasks) {

  public record Task(
      long id,
      int seqNo,
      String approverRole,
      WorkflowTaskStatus status,
      Long actedBy,
      LocalDateTime actedAt,
      String opinion) {}
}
