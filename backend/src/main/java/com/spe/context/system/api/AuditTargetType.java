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
  TEMPLATE
}
