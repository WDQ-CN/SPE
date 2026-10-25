package com.spe.context.evaluation.internal.result;

import java.time.LocalDate;
import lombok.Data;

/** 结果报表入口的本上下文投影：任务 + 已生效评估单数。 */
@Data
public class ResultTaskRow {

  private Long taskId;

  private String taskName;

  private Integer year;

  private Integer quarter;

  private LocalDate deadline;

  private String status;

  private Long resultCount;
}
