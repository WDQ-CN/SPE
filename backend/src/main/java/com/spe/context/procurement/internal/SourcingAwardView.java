package com.spe.context.procurement.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 定标详情投影。 */
public record SourcingAwardView(
    long id,
    String awardNo,
    long rfqId,
    long supplierId,
    long quoteId,
    BigDecimal awardAmount,
    String currency,
    SourcingAwardStatus status,
    String reason,
    Long approvedBy,
    LocalDateTime approvedAt,
    int version,
    LocalDateTime createdAt,
    List<Line> lines) {

  public record Line(
      long id,
      long rfqLineId,
      long quoteLineId,
      BigDecimal awardedQty,
      BigDecimal unitPrice,
      BigDecimal amount) {}
}
