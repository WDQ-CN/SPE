package com.spe.context.alert.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 整改列表的对外投影（W3；W4 的 `/actions` 列表依赖此契约）。
 *
 * <p>`overdue` 由服务端计算：截止日期早于今天且未进入终态（VERIFIED/CANCELED）才算超期；
 * 前端只做视觉标记，不自行按本地时区重算。
 */
public record CorrectiveActionSummaryView(
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
    String status,
    boolean overdue,
    /** 已重新提交、等待下期定稿后重新验证；列表与详情共用同一判定。 */
    boolean reverifyPending,
    Integer verifyYear,
    Integer verifyQuarter,
    BigDecimal verifyScore,
    String verifyOutcome,
    int version,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

  static CorrectiveActionSummaryView of(CorrectiveActionRow row, LocalDate today) {
    return new CorrectiveActionSummaryView(
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
        row.getStatus(),
        isOverdue(row.getStatus(), row.getDueDate(), today),
        isReverifyPending(row.getStatus(), row.getVerifiedAt(), row.getVerifyYear()),
        row.getVerifyYear(),
        row.getVerifyQuarter(),
        row.getVerifyScore(),
        row.getVerifyOutcome(),
        row.getVersion(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  static boolean isOverdue(String status, LocalDate dueDate, LocalDate today) {
    return dueDate != null
        && dueDate.isBefore(today)
        && !CorrectiveActionStatus.VERIFIED.name().equals(status)
        && !CorrectiveActionStatus.CANCELED.name().equals(status);
  }

  /** 重开后再提交：上次经理决定留下 verified_at，验证器回填新周期时才会清空。 */
  static boolean isReverifyPending(
      String status, LocalDateTime verifiedAt, Integer verifyYear) {
    return CorrectiveActionStatus.WAITING_VERIFICATION.name().equals(status)
        && verifiedAt != null
        && verifyYear != null;
  }
}
