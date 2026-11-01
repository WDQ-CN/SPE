package com.spe.context.alert.internal;

import java.math.BigDecimal;
import lombok.Data;

/** 下期验证的候选周期（D5：同供应商、严格晚于基线、最早的 EFFECTIVE 评估单）。 */
@Data
public class NextEffectivePeriodRow {

  private Long taskSupplierId;

  private Integer year;

  private Integer quarter;

  private BigDecimal totalScore;
}
