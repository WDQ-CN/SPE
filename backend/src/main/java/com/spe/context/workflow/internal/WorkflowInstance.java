package com.spe.context.workflow.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 工作流实例。 */
@Data
@TableName("workflow_instance")
public class WorkflowInstance {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String instanceNo;
  private Long definitionId;
  private String docType;
  private Long docId;
  private Long companyId;
  private BigDecimal amount;
  private WorkflowInstanceStatus status;
  private Integer currentSeq;
  private Long startedBy;
  private LocalDateTime startedAt;
  private LocalDateTime completedAt;
  private Integer version;
}
