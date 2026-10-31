package com.spe.context.system.api;

/**
 * 审计目标类型。
 *
 * <p>取值锚定 `V4__audit_log.sql` 的 `target_type` 列（VARCHAR(32)、不加 CHECK）：P1 建于 USER/SUPPLIER/SETTING，
 * **P2 起新增 TEMPLATE**（模板/版本/维度/指标的变更都挂在它下面，12 §E8）。迁移脚本合入即冻结（06 §1.3），
 * 所以 V4 里那句列注释不会跟着更新——**以本枚举为准**。
 */
public enum AuditTargetType {
  USER,
  SUPPLIER,
  SETTING,
  /** P2 起：模板、模板版本、维度、指标（四者共用一个目标类型，具体对象由 action 区分）。 */
  TEMPLATE,
  /** 评估任务（目标 id = eval_task.id；建/改/发布/改派等任务级动作）。 */
  TASK,
  /**
   * 评估单（目标 id = task_supplier.id；提交/审批/定稿/解锁/导入等评估单级动作）。
   *
   * <p>与 TASK 分开是为了让「按任务查审计」与「按评估单查审计」不再靠猜 id 语义（03 §51 P2-11）。
   * 历史上把评估单级动作记成 TASK + task_supplier_id 的旧行保留原样（审计只增不改）。
   */
  TASK_SUPPLIER,
  /** M6 起：开放 API Token（生成/停用；目标 id = api_token.id）。 */
  TOKEN
}
