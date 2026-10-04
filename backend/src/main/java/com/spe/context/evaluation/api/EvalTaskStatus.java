package com.spe.context.evaluation.api;

/**
 * 评估任务状态（02 §3 状态机、02 §3.1 的 {@code eval_task.status}、06 §2.1 的状态常量）。
 *
 * <p>P3 只实现 {@code DRAFT → IN_PROGRESS} 一条迁移（发布任务，05 §5.5 T1）。其余两个常量按
 * 07 §4「枚举值预留、入口后置」先留着：
 *
 * <ul>
 *   <li>{@code COMPLETED}：01 J3「全部评估单生效 → 任务已完成」要等 <b>P4 的定稿</b>才有触发点，
 *       因此 <b>P3 不写"自动完成"的判定代码</b>（13 §E3：没有定稿时那段逻辑永远不会被执行，
 *       写它就是一个"声明了但没人消费"的空实现）。
 *   <li>{@code CANCELED}：01 J6 任务取消已后置（07 §59），**没有任何入口**；但 01 J4 的唯一性判定
 *       按 {@code status != CANCELED} 实现——这条判定在 P3 就写进发布校验，否则 J6 落地时要改唯一性逻辑。
 * </ul>
 *
 * <p>常量名与库中 {@code eval_task.status}（V11）逐字一致：枚举名即落库的字符串
 * （05 §8.1「状态/类型/来源用 VARCHAR 存枚举常量」）。
 */
public enum EvalTaskStatus {
  DRAFT,
  IN_PROGRESS,
  COMPLETED,
  CANCELED
}
