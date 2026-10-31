package com.spe.common.sql;

/**
 * 「已生效结果」的唯一判定条件（03 §51 P2-8）。
 *
 * <p>必须同时满足：评估单状态为 EFFECTIVE，且所属任务未被取消——CANCELED 任务即使历史上留下
 * EFFECTIVE 的评估单，也不能进入分析/预警/结果列表。各 Mapper 用字符串拼接复用，避免 9 份手抄漂移。
 */
public final class EffectiveResults {

  /** 必须同时引用 {@code ts}（task_supplier）与 {@code t}（eval_task）两个别名的查询。 */
  public static final String CONDITION = "ts.status = 'EFFECTIVE' AND t.status != 'CANCELED'";

  private EffectiveResults() {}
}
