package com.spe.context.evaluation.internal.sheet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.spe.context.evaluation.api.SheetStatus;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评分单实体（V11 / 02 §3.1 / 01 J2）：一个评估单里的**一个维度**，指派给一名评估人。
 *
 * <p>{@code assigneeId} 可空：模板维度的建议部门下没有在职评估人时置 `UNASSIGNED`（不猜部门，
 * 13 §E12），由采购经理改派。
 */
@Data
@TableName("sheet")
public class Sheet {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long taskSupplierId;

  /** 维度 id（指向**发布版本**的维度；评分口径来自冻结快照，05 ADR-3）。 */
  private Long dimensionId;

  private Long assigneeId;

  private SheetStatus status;

  private Integer version;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
