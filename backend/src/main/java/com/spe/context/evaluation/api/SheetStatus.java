package com.spe.context.evaluation.api;

/**
 * 评分单状态（02 §3 状态机、02 §3.1 的 {@code sheet.status}、06 §2.1 的状态常量）。
 *
 * <p>三态在 P3 <b>全部可达</b>：{@code UNASSIGNED}（待指派）→（自动指派或改派）→ {@code SCORING}
 * （评分中）→（提交）→ {@code SUBMITTED}（已提交，锁定不可改，01 E4）。改派只允许前两者（02 §3）；
 * 驳回解锁回 {@code SCORING} 属 P4。常量名与库中列值逐字一致（V11）。
 */
public enum SheetStatus {
  UNASSIGNED,
  SCORING,
  SUBMITTED
}
