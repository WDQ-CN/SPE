package com.spe.context.alert.internal;

import com.spe.context.system.api.AuditTrailEntry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 整改详情的对外投影（W3；W4 的详情页与所有写操作响应共用）。
 *
 * <p>写接口返回详情而不是实体：调用方拿到的 `version` 必须是最新值，下一次守卫 UPDATE 才不会
 * 拿着旧版本撞 3001。
 */
public record CorrectiveActionDetailView(
    long id,
    long alertId,
    String alertType,
    String alertTitle,
    long taskSupplierId,
    long supplierId,
    String supplierCode,
    String supplierName,
    Long dimensionId,
    String dimensionName,
    int baselineYear,
    int baselineQuarter,
    BigDecimal baselineScore,
    BigDecimal targetScore,
    long ownerId,
    String ownerName,
    LocalDate dueDate,
    String measure,
    String expectedResult,
    String status,
    boolean overdue,
    String submitComment,
    LocalDateTime submittedAt,
    Integer verifyYear,
    Integer verifyQuarter,
    Long verifyTaskSupplierId,
    BigDecimal verifyScore,
    String verifyOutcome,
    String verifyComment,
    Long verifiedBy,
    String verifiedByName,
    LocalDateTime verifiedAt,
    String canceledReason,
    long createdBy,
    String createdByName,
    int version,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    List<AuditTrailEntry> auditTrail) {

  static CorrectiveActionDetailView of(
      CorrectiveActionRow row, LocalDate today, List<AuditTrailEntry> auditTrail) {
    return new CorrectiveActionDetailView(
        row.getId(),
        row.getAlertId(),
        row.getAlertType(),
        row.getAlertTitle(),
        row.getTaskSupplierId(),
        row.getSupplierId(),
        row.getSupplierCode(),
        row.getSupplierName(),
        row.getDimensionId(),
        row.getDimensionName(),
        row.getBaselineYear(),
        row.getBaselineQuarter(),
        row.getBaselineScore(),
        row.getTargetScore(),
        row.getOwnerId(),
        row.getOwnerName(),
        row.getDueDate(),
        row.getMeasure(),
        row.getExpectedResult(),
        row.getStatus(),
        CorrectiveActionSummaryView.isOverdue(row.getStatus(), row.getDueDate(), today),
        row.getSubmitComment(),
        row.getSubmittedAt(),
        row.getVerifyYear(),
        row.getVerifyQuarter(),
        row.getVerifyTaskSupplierId(),
        row.getVerifyScore(),
        row.getVerifyOutcome(),
        row.getVerifyComment(),
        row.getVerifiedBy(),
        row.getVerifiedByName(),
        row.getVerifiedAt(),
        row.getCanceledReason(),
        row.getCreatedBy(),
        row.getCreatedByName(),
        row.getVersion(),
        row.getCreatedAt(),
        row.getUpdatedAt(),
        auditTrail);
  }
}
