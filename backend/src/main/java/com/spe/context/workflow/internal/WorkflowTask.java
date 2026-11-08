package com.spe.context.workflow.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 工作流任务。 */
@Data
@TableName("workflow_task")
public class WorkflowTask {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long instanceId;
  private Integer seqNo;
  private String approverRole;
  private WorkflowTaskStatus status;
  private LocalDateTime assignedAt;
  private Long actedBy;
  private LocalDateTime actedAt;
  private String opinion;
}
