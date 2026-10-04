package com.spe.context.evaluation.internal.sheet;

import java.time.LocalDate;
import lombok.Data;

/**
 * 「我的待办」的**本上下文投影**（13 §E13）：只 join 自己上下文的表（sheet / task_supplier / eval_task）。
 *
 * <p>供应商名与维度名**不在 SQL 里 join**——那两张表属 supplier / template 上下文，跨上下文要经对方的
 * BTapiBT 门面（05 §4.3/§8.3）。所以这里只取 id 与自己的字段，名称由 Service 批量补齐
 * （两次批量查询，不是逐行回查——这正是 E13 想避免的那件事）。
 */
@Data
public class MySheetRow {

  private Long sheetId;

  private Long taskSupplierId;

  private Long supplierId;

  private Long dimensionId;

  private Long taskId;

  private String taskName;

  private Long templateVersionId;

  private LocalDate deadline;

  private String status;

  /** 评分单版本（待办直接跳打分页后要用它提交，13 §E18）。 */
  private Integer version;
}
