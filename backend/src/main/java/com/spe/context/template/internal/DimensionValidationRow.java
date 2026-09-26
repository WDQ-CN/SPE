package com.spe.context.template.internal;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 发布校验用的**维度聚合行**（12 §E4/E12 的五类校验之一二四）。
 *
 * <p>一条 `LEFT JOIN + GROUP BY` 就把"每个维度的权重、指标数、指标权重合计"全取回来——校验需要跨行信息，
 * 逐维度再去查指标就是 N+1。
 *
 * <p>同样是普通类而非 record：MyBatis 按"无参构造 + setter"映射（项目里的 record 都是手工转换的读模型）。
 */
@Data
public class DimensionValidationRow {

  private Long id;

  private String name;

  /** 维度权重（模板内合计须为 100）。 */
  private BigDecimal dimensionWeight;

  /** 该维度下的指标数（**0 表示这个维度一个指标都没有**，发布时拒绝）。 */
  private Long indicatorCount;

  /** 该维度内指标权重合计（须为 100；没有指标时为 0）。 */
  private BigDecimal indicatorWeightSum;
}
