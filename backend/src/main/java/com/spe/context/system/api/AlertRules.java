package com.spe.context.system.api;

/**
 * 预警规则（01 W1 / SM3；M5 W2，`22 §2 D9`）。
 *
 * <p>默认值**由代码持有**（{@link #defaults()}），设置键缺失时返回默认值、不落库——
 * 与等级阈值同一条口径（10 §E9），避免默认值同时存在于 SQL 与代码两处。
 *
 * <p>校验只此一处（{@link #satisfies()}）：阈值 0~100、连续期数 1~8、累计降幅 0~100。
 */
public record AlertRules(
    boolean enabled, int totalThreshold, int keyDimensionThreshold, int trendPeriods, int trendDrop) {

  /** 01 W1 的出厂默认：启用；总分/关键维度阈值 70；连续 2 期下降且累计 ≥5 分。 */
  public static AlertRules defaults() {
    return new AlertRules(true, 70, 70, 2, 5);
  }

  public boolean satisfies() {
    return totalThreshold >= 0
        && totalThreshold <= 100
        && keyDimensionThreshold >= 0
        && keyDimensionThreshold <= 100
        && trendPeriods >= 1
        && trendPeriods <= 8
        && trendDrop >= 0
        && trendDrop <= 100;
  }
}
