package com.spe.context.project.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 工时单详情投影。 */
public record TimesheetView(
    long id,
    String timesheetNo,
    long companyId,
    long userId,
    TimesheetStatus status,
    BigDecimal totalHours,
    BigDecimal totalAmount,
    LocalDateTime createdAt,
    LocalDateTime submittedAt,
    LocalDateTime approvedAt,
    int version,
    List<Line> lines) {

  public record Line(
      long id,
      int lineNo,
      long projectId,
      Long wbsId,
      LocalDate workDate,
      BigDecimal hours,
      BigDecimal rate,
      BigDecimal amount) {}
}
