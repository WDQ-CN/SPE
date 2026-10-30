package com.spe.context.alert.internal;

import java.time.LocalDateTime;

/** 预警列表/处理的对外投影（02 §4 `GET /alerts`；实体不出上下文）。 */
public record AlertView(
    long id,
    long taskSupplierId,
    long supplierId,
    String supplierCode,
    String supplierName,
    int year,
    int quarter,
    String type,
    String title,
    String content,
    String status,
    Long handledBy,
    String handledByName,
    String handledComment,
    LocalDateTime handledAt,
    LocalDateTime createdAt) {

  static AlertView of(AlertRow row) {
    return new AlertView(
        row.getId(),
        row.getTaskSupplierId(),
        row.getSupplierId(),
        row.getSupplierCode(),
        row.getSupplierName(),
        row.getYear(),
        row.getQuarter(),
        row.getType(),
        row.getTitle(),
        row.getContent(),
        row.getStatus(),
        row.getHandledBy(),
        row.getHandledByName(),
        row.getHandledComment(),
        row.getHandledAt(),
        row.getCreatedAt());
  }
}
