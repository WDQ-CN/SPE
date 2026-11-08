package com.spe.context.project.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 项目详情投影。 */
public record ProjectView(
    long id,
    String projectNo,
    long companyId,
    Long customerId,
    String name,
    String currency,
    BigDecimal budgetAmount,
    BigDecimal defaultLaborRate,
    ProjectStatus status,
    LocalDate startDate,
    LocalDate endDate,
    long managerId,
    LocalDateTime createdAt,
    LocalDateTime approvedAt,
    LocalDateTime closedAt,
    int version,
    BigDecimal actualCost,
    List<Wbs> wbs,
    List<Budget> budgets) {

  public record Wbs(long id, Long parentId, String code, String name, BigDecimal budget, WbsStatus status) {}

  public record Budget(
      long id,
      String versionNo,
      BigDecimal laborAmount,
      BigDecimal purchaseAmount,
      BigDecimal expenseAmount,
      BigDecimal otherAmount,
      BigDecimal totalAmount,
      ProjectBudgetStatus status) {}
}
