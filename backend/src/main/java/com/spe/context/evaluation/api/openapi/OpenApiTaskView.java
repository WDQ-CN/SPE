package com.spe.context.evaluation.api.openapi;

import java.time.LocalDate;
import java.util.List;

/** 开放 API 的进行中任务（M6 I1）：任务字段 + 参与供应商，外部系统据此拿到 supplierCode。 */
public record OpenApiTaskView(
    long taskId,
    String name,
    int year,
    int quarter,
    LocalDate deadline,
    List<OpenApiSupplierView> suppliers) {

  public OpenApiTaskView {
    suppliers = List.copyOf(suppliers);
  }
}
