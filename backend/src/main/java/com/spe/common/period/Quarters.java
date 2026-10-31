package com.spe.common.period;

/** 日历季度的唯一换算点（03 §51 P3-2）。 */
public final class Quarters {

  private Quarters() {}

  /** 上一个日历季度：Q1 → 上一年 Q4，其余 quarter-1。返回 `{year, quarter}`。 */
  public static int[] previous(int year, int quarter) {
    return quarter == 1 ? new int[] {year - 1, 4} : new int[] {year, quarter - 1};
  }
}
