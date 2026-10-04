package com.spe.context.evaluation.internal.task;

import java.math.BigDecimal;
import java.util.List;

/**
 * 进度看板（01 J3 / 13 §E14）：每个供应商一行，含**试算得分**（不完整时为 null）。
 *
 * <p>实现要点：一条聚合查询取"行 + 计数"，再把该任务下的 score **一次性**取回内存，按供应商分组调
 * ` ScoreCalculator`（纯函数、无 IO）。因此 SQL 条数与供应商数**无关**——这一条由集成用例用
 * `performance_schema` 的语句计数证明（不是靠延迟）。
 *
 * @param rows 每供应商一行，按 task_supplier.id 升序
 */
public record TaskProgress(long taskId, List<Row> rows) {

  /**
   * 一行。
   *
   * @param trialScore 试算总分；该供应商任一维度不完整时为 null（13 §E6：不猜、不给 0）
   * @param trialGrade 试算等级；同上为 null
   * @param unassignedSheets 未指派的评分单数（J3 要看"还有几张没人认领"）
   * @param unsubmittedSheets 未提交的评分单数
   */
  public record Row(
      long taskSupplierId,
      long supplierId,
      String supplierCode,
      String supplierName,
      String status,
      Integer currentNode,
      BigDecimal trialScore,
      String trialGrade,
      boolean complete,
      long unassignedSheets,
      long unsubmittedSheets) {}
}
