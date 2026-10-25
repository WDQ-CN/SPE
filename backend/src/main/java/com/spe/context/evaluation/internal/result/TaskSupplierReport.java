package com.spe.context.evaluation.internal.result;

import com.spe.context.evaluation.api.TaskSupplierStatus;
import com.spe.context.template.api.IndicatorType;
import com.spe.context.template.api.ScoreMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评估报告（01 R3 / 02 §4）：`GET /task-suppliers/{id}/report` 的响应。
 *
 * <p>两套口径必须在响应里可区分（15 §E15）：
 * <ul>
 *   <li>{@code scoreMode = SNAPSHOT}：已生效，维度分/等级取 {@code task_supplier_dimension_result} 快照；
 *   <li>{@code scoreMode = TRIAL}：未生效，降级为 `ScoreCalculator` 试算，{@code complete=false} 表示数据不全。
 * </ul>
 *
 * <p>时间线按 `(round, seq)` 平铺返回（D3 裁定），分块是前端展示细节。
 */
public record TaskSupplierReport(
    long taskSupplierId,
    long taskId,
    String taskName,
    int year,
    int quarter,
    LocalDate deadline,
    long supplierId,
    String supplierCode,
    String supplierName,
    TaskSupplierStatus status,
    int currentNode,
    int round,
    int version,
    String scoreMode,
    BigDecimal totalScore,
    String totalGrade,
    boolean complete,
    /**
     * 当前登录人**此刻**是否就是这一节点的审批人（`03 §41.3` 的 P2-27、`21 §3.W1-3`）。
     *
     * <p>第二层判据留在服务端查库（与 15 §E8 同哲学）：前端不存 uid，也就不可能自己算对；
     * 它只消费这个布尔值决定「通过/驳回」按钮的显隐。
     */
    boolean actableNow,
    List<Dimension> dimensions,
    List<TimelineEntry> timeline) {

  /** 单维度结果（快照或试算），附带指标明细（01 R3：原始值 / 换算分 / 来源）。 */
  public record Dimension(
      long dimensionId,
      String dimensionName,
      BigDecimal weight,
      BigDecimal score,
      String grade,
      boolean complete,
      List<Indicator> indicators) {}

  public record Indicator(
      long indicatorId,
      String code,
      String name,
      BigDecimal weight,
      IndicatorType type,
      ScoreMode scoreMode,
      BigDecimal rawValue,
      BigDecimal scoreValue,
      String unit,
      String source) {}

  public record TimelineEntry(
      int round,
      int seq,
      long approverId,
      String approverName,
      String action,
      String comment,
      LocalDateTime createdAt) {}
}
