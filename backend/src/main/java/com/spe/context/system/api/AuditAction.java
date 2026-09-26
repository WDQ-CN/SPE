package com.spe.context.system.api;

/**
 * 审计动作码词表（06 §2.8）：`&lt;对象&gt;_&lt;动作&gt;` 全大写下划线，**只增不改**。
 *
 * <p>用枚举而非裸字符串，是为了让「未登记的动作码」在编译期就暴露；新增动作码时同步在 06 §2.8 登记。
 * 本表当前收录 P1 在用集合，后续阶段按同一规则追加 TEMPLATE_* / TASK_* / SHEET_* / APPROVAL_* / TOKEN_*。
 */
public enum AuditAction {
  AUTH_LOGIN_SUCCESS,
  AUTH_LOGIN_FAILED,
  /** 登录失败次数超限、账号被临时锁定（03 §16 的 SEC-5）：每个锁定事件只留一条，锁定期内的重复请求不再写库。 */
  AUTH_LOGIN_BLOCKED,
  USER_CREATE,
  USER_UPDATE,
  USER_STATUS_CHANGE,
  USER_PASSWORD_RESET,
  /** 本人改口令（01 A3）：06 §2.8 的 P1 词表原文未列，按「只增不改」规则新增并已回写该节。 */
  USER_PASSWORD_CHANGE,
  SETTING_UPDATE,
  SUPPLIER_CREATE,
  SUPPLIER_UPDATE,
  SUPPLIER_STATUS_CHANGE,
  ACCESS_DENIED
}
