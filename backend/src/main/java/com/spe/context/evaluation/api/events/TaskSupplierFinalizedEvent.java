package com.spe.context.evaluation.api.events;

import com.spe.common.event.DomainEvent;
import java.math.BigDecimal;
import java.util.List;

/**
 * 评估单已定稿生效（05 §5.3 事件契约表：{@code taskSupplierId、supplierId、周期、总分/总等级、维度结果}
 * → alert 规则判定；notify 通知采购经理）。
 *
 * <p>发布点：05 §5.5 T3「定稿」——守卫迁移到 {@code EFFECTIVE}、总分/总等级与维度快照落库后发布。
 * 监听器同步执行于定稿事务内（05 ADR-9）：任一监听器失败，定稿整体回滚。
 *
 * <p>载荷为不可变 record（05 §5.3、ArchUnit R-D）；{@code dimensions} 构造时拷贝。
 * alert 的消费方属 M5（预警），P4 只接 notify（15 §E7）。
 *
 * @param totalScore 总分（定稿前已断言每维非空，故此处必有值）
 * @param totalGrade 总等级 A/B/C/D
 * @param dimensions 维度结果快照（口径与 {@code task_supplier_dimension_result} 一致）
 * @param firstEffective 本期是否首次生效：只有 true 才允许触发趋势类预警（01 W2）；
 *     由 {@code FinalizeService} 按「上一轮末级是否已有 APPROVE」判定，驳回重提仍算首次，
 *     解锁重评再定稿则不算
 */
public record TaskSupplierFinalizedEvent(
    long taskSupplierId,
    long supplierId,
    int year,
    int quarter,
    BigDecimal totalScore,
    String totalGrade,
    List<DimensionResult> dimensions,
    boolean firstEffective)
    implements DomainEvent {

  public TaskSupplierFinalizedEvent {
    dimensions = List.copyOf(dimensions);
  }

  /**
   * 单维度的结果。
   *
   * <p>{@code dimensionName} / {@code weight} 是定稿当时的快照值（冗余存储，模板版本发布即冻结），
   * 报告与预警都按它读，不再实时联表（05 §8.2、15 §E3）。
   */
  public record DimensionResult(
      long dimensionId,
      String dimensionName,
      BigDecimal weight,
      BigDecimal score,
      String grade) {}
}
