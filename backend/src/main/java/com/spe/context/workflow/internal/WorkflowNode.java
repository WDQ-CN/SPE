package com.spe.context.workflow.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 工作流节点（串行，按角色审批）。 */
@Data
@TableName("workflow_node")
public class WorkflowNode {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long definitionId;
  private Integer seqNo;
  private String name;
  private String approverRole;
}
