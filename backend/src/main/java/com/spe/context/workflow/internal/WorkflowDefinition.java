package com.spe.context.workflow.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 工作流定义（V1030）。 */
@Data
@TableName("workflow_definition")
public class WorkflowDefinition {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String code;
  private String name;
  private String docType;
  private String versionNo;
  private BigDecimal conditionMinAmount;
  private BigDecimal conditionMaxAmount;
  private WorkflowDefinitionStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
  private LocalDateTime activatedAt;
}
