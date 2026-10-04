package com.spe.context.evaluation.internal.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 审批链定义（V11 / 02 §3 / 01 J1）：挂在**任务**上，{@code seq} 从 1 起（串行审批）。
 *
 * <p>P3 只建与读：审批流转（P4）才写审批记录（`approval_record`，留到 P4 的 V13）。
 */
@Data
@TableName("approval_node")
public class ApprovalNode {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long taskId;

  private Long approverId;

  private Integer seq;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
