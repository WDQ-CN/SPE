package com.spe.context.quality.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 检验单投影。 */
public record InspectionOrderView(
    long id,
    String orderNo,
    long companyId,
    long planId,
    String sourceType,
    Long sourceId,
    Long sourceLineId,
    long itemId,
    Long supplierId,
    String lotNo,
    BigDecimal qty,
    InspectionStatus status,
    LocalDateTime createdAt,
    LocalDateTime completedAt,
    Long ncrId,
    List<Result> results) {

  public record Result(
      long id,
      int lineNo,
      Long characteristicId,
      String name,
      String measuredValue,
      InspectionResultStatus result,
      String remark) {}
}
