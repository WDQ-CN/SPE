package com.spe.context.evaluation.internal.sheet;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评分单的上下文投影（写分 / 提交 / 试算共用）：只含判断与换算需要的字段。
 *
 * <p>刻意不用实体：它跨了三张表（sheet → task_supplier → eval_task），用投影比"查三个实体再拼"少两次往返，
 * 也不会把别处的字段顺手带进来。
 */
@Data
public class SheetContextRow {

  private Long sheetId;

  private Long assigneeId;

  /** 评分单状态（VARCHAR 落库；这里用 String 承接，避免 ResultMap 额外配置）。 */
  private String sheetStatus;

  private Long taskSupplierId;

  private Long dimensionId;

  private Integer sheetVersion;

  private Long taskId;

  /** 任务的模板版本：写分时用它取**已发布快照**（05 ADR-3）。 */
  private Long templateVersionId;

  private LocalDateTime createdAt;
}
