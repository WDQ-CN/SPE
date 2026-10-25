package com.spe.context.evaluation.internal.approval;

import java.time.LocalDate;
import lombok.Data;

/**
 * 「我的审批待办」的**本上下文投影**（15 §E8）：只 join 本上下文的 `task_supplier` / `approval_node` /
 * `eval_task`，供应商名称由 Service 经门面批量补齐。
 */
@Data
public class ApprovalTodoRow {

  private Long taskSupplierId;

  private Long taskId;

  private String taskName;

  private Integer year;

  private Integer quarter;

  private LocalDate deadline;

  private Long supplierId;

  private String status;

  private Integer currentNode;

  private Integer round;

  private Integer version;
}
