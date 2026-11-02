package com.spe.context.alert.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 整改任务的分页/详情投影行（W3；29 §3.5）。
 *
 * <p>读侧把供应商、维度、预警与三个操作人的展示名一次带出，避免 W4 为一行数据回查多张表；
 * 实体仍留在上下文内部，Controller 只序列化 {@link CorrectiveActionSummaryView} /
 * {@link CorrectiveActionDetailView}。
 */
@Data
public class CorrectiveActionRow {

  private Long id;

  private Long alertId;

  private String alertType;

  private String alertTitle;

  private Long taskSupplierId;

  private Long supplierId;

  private String supplierCode;

  private String supplierName;

  private Long dimensionId;

  private String dimensionName;

  private Integer baselineYear;

  private Integer baselineQuarter;

  private BigDecimal baselineScore;

  private BigDecimal targetScore;

  private Long ownerId;

  private String ownerName;

  private LocalDate dueDate;

  private String measure;

  private String expectedResult;

  private String status;

  private String submitComment;

  private LocalDateTime submittedAt;

  private Integer verifyYear;

  private Integer verifyQuarter;

  private Long verifyTaskSupplierId;

  private BigDecimal verifyScore;

  private String verifyOutcome;

  private Long verifiedBy;

  private String verifiedByName;

  private String verifyComment;

  private LocalDateTime verifiedAt;

  private String canceledReason;

  private Long createdBy;

  private String createdByName;

  private Integer version;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;
}
