package com.spe.context.quality.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 检验计划投影。 */
public record InspectionPlanView(
    long id,
    String planNo,
    long companyId,
    long itemId,
    String itemCode,
    String itemName,
    Long supplierId,
    InspectionType inspectionType,
    String version,
    InspectionPlanStatus status,
    String samplingPlan,
    BigDecimal aql,
    LocalDateTime createdAt,
    LocalDateTime activatedAt,
    List<Characteristic> characteristics) {

  public record Characteristic(
      long id, int lineNo, String name, String spec, boolean critical, String method) {}
}
