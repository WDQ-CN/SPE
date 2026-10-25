package com.spe.context.evaluation.internal.result;

import java.math.BigDecimal;
import java.util.List;

/**
 * 任务级结果列表（01 R2 / 02 §4）：`GET /tasks/{id}/results` 的响应，**只含已生效**的评估单。
 *
 * <p>`dimensions` 来自定稿快照（`task_supplier_dimension_result`），不再实时联表（05 §8.2）。
 */
public record TaskResults(
    long taskId, String taskName, int year, int quarter, List<Row> rows) {

  public record Row(
      long taskSupplierId,
      long supplierId,
      String supplierCode,
      String supplierName,
      BigDecimal totalScore,
      String totalGrade,
      List<DimensionScore> dimensions) {}

  public record DimensionScore(
      long dimensionId,
      String dimensionName,
      BigDecimal weight,
      BigDecimal score,
      String grade) {}
}
