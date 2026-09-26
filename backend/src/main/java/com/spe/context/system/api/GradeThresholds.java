package com.spe.context.system.api;

import java.math.BigDecimal;

/**
 * 等级阈值（01 §6.3 / 10 §E9）：A ≥ a、B ≥ b、C ≥ c、D < c。
 *
 * <p>默认值**由代码持有**（本类 {@link #defaults()}），设置键缺失时返回默认值、不落库——
 * 避免默认值同时存在于 SQL 与代码两处而漂移（10 §E9）。
 *
 * <p>约束：{@code 0 ≤ d ≤ c ≤ b ≤ a ≤ 100}，**允许相等**（等于停用该档）。校验口径只此一处，
 * 服务层与前端都引用它（前端仍以后端为准，06 §3.2）。
 *
 * <p>数值一律 {@link BigDecimal}（06 §2.5：分值域禁止 double）。
 */
public record GradeThresholds(BigDecimal a, BigDecimal b, BigDecimal c, BigDecimal d) {

  /** 01 §6.3 的出厂默认：A≥90 / B≥80 / C≥70 / D<70。 */
  public static GradeThresholds defaults() {
    return new GradeThresholds(
        new BigDecimal("90"), new BigDecimal("80"), new BigDecimal("70"), new BigDecimal("0"));
  }

  /** 01 §6.3 的阈值约束；返回 false 由服务层转 `THRESHOLD_INVALID(2005)`。 */
  public boolean satisfiesOrdering() {
    if (a == null || b == null || c == null || d == null) {
      return false;
    }
    BigDecimal zero = BigDecimal.ZERO;
    BigDecimal hundred = new BigDecimal("100");
    return d.compareTo(zero) >= 0
        && c.compareTo(d) >= 0
        && b.compareTo(c) >= 0
        && a.compareTo(b) >= 0
        && a.compareTo(hundred) <= 0;
  }
}
