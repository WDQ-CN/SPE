package com.spe.context.evaluation.internal.task;

import lombok.Data;

/** 进度看板的一行（13 §E14 的聚合投影）：评估单状态 + 当前节点 + 两个计数。 */
@Data
public class TaskProgressRow {

  private Long taskSupplierId;

  private Long supplierId;

  private String status;

  private Integer currentNode;

  /** 未指派的评分单数（assignee_id 为空）。 */
  private Long unassignedSheets;

  /** 未提交的评分单数（status <> SUBMITTED）。 */
  private Long unsubmittedSheets;
}
