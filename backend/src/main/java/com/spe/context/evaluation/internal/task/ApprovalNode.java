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

  /**
   * 审批链的最大级数。**01 只写"多级串行审批"、没给数字**，这个 10 是防御性上限（18 §6 裁决点 5 拍定）：
   * 没有它，`approverIds` 就是一个无界集合，超长 `IN (…)` 与超长的审批链插入都进得来。
   * 上限本身由 DTO 上的 `@Size(max = ApprovalNode.MAX_LEVELS)` 执行，两处引用这同一个数。
   */
  public static final int MAX_LEVELS = 10;

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long taskId;

  private Long approverId;

  private Integer seq;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
