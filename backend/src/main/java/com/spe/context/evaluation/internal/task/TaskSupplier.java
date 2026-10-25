package com.spe.context.evaluation.internal.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.evaluation.api.TaskSupplierStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评估单实体（V11 / 02 §3.1 / 05 §5.4）：一个任务里的**一个供应商**。
 *
 * <p>总分与总等级**P4 定稿时才写**，因此可空（02 §3.1 拍板①）：`null` 表示"还没算过"，
 * 与"算出来是 0 分"不会混淆。
 *
 * <p>P3 只产生 `SCORING → PENDING_APPROVAL` 一条迁移（W4 的提交）；`APPROVING`/`EFFECTIVE` 是 P4 的审批与定稿。
 */
@Data
@TableName("task_supplier")
public class TaskSupplier {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long taskId;

  private Long supplierId;

  private TaskSupplierStatus status;

  /** 当前审批节点序号；**提交时置 1（T2）、驳回清零为 0**（02 §3、15 §E5）。 */
  private Integer currentNode;

  /** 审批轮次：驳回重提时 +1（`15 §E11`，随 `V13` 加列）；与状态回退写在**同一条**守卫 UPDATE 里。 */
  private Integer round;

  private BigDecimal totalScore;

  private String totalGrade;

  private Integer version;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
