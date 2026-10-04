package com.spe.context.evaluation.api;

/**
 * 评估单状态（02 §3 状态机、02 §3.1 的 {@code task_supplier.status}、06 §2.1 的状态常量）。
 *
 * <p>P3 只产生两个状态：{@code SCORING} →（最后一张评分单提交）→ {@code PENDING_APPROVAL}
 * （05 §5.5 T2）。{@code APPROVING} 与 {@code EFFECTIVE} 属 P4 的审批与定稿（07 §31），
 * 本阶段<b>没有任何端点能进入</b>；驳回（P4）把状态迁回 {@code SCORING}，不设 {@code REJECTED} 态（01 P2）。
 *
 * <p>当前审批节点序号另列在 {@code current_node}（05 §5.5 T2 的 {@code SET … current_node=1}），不复用本枚举。
 * 常量名与库中列值逐字一致（V11）。
 */
public enum TaskSupplierStatus {
  SCORING,
  PENDING_APPROVAL,
  APPROVING,
  EFFECTIVE
}
