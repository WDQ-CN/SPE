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
  // ---- P2 起：模板（06 §2.8 的 P2 词表；动作码只增不改）----
  TEMPLATE_CREATE,
  TEMPLATE_UPDATE,
  /** 草稿 → 已发布（守卫 UPDATE，0 行 → 3001）。 */
  TEMPLATE_VERSION_PUBLISH,
  /**
   * 编辑已发布版本时**自动复制出新草稿**（12 §E5）。
   *
   * <p>单独留痕：它是「发布之后内容又变了」的唯一线索——原版本不可变，改动只会落在新草稿上，
   * 没有这条记录就无法回答"这份草稿是怎么冒出来的"。targetId 记**新草稿**的版本 id，
   * detail 里带 fromVersionId/fromVersionNo（12 §E8）。
   */
  TEMPLATE_VERSION_AUTO_COPY,
  DIMENSION_CREATE,
  DIMENSION_UPDATE,
  DIMENSION_DELETE,
  INDICATOR_CREATE,
  INDICATOR_UPDATE,
  INDICATOR_DELETE,
  // ---- P3 起：任务与评分单（13 §E20 的词表；动作码只增不改）----
  TASK_CREATE,
  TASK_UPDATE,
  /** 发布（T1）：detail 记供应商数、评分单数、指派成功/未指派数。 */
  TASK_PUBLISH,
  /** 改派（01 J2）：detail 记 sheetId 与 before/after 的指派人与 sheetId。 */
  SHEET_ASSIGN,
  /** 提交评分单（T2）：detail 记 sheetId、提交的指标数（**不记分值**，13 §E20 的敏感面）。 */
  SHEET_SUBMIT,
  /** Excel 导入（01 E3）：detail 记批次号、写入格数、跳过格数、错误数——**不记内容**。 */
  SHEET_IMPORT,
  /** 审批通过（T5）：detail 记评估单、节点序号、轮次（15 §E10）。 */
  APPROVAL_APPROVE,
  /** 审批驳回（T5）：detail 记评估单、节点序号、轮次与**意见摘要**（用户输入，按 06 §2.8 截断）。 */
  APPROVAL_REJECT,
  /**
   * 定稿（T3）：detail 记总分、总等级、维度数，**并带 `taskCompleted`**（完单 SQL 影响行数是否为 1）。
   *
   * <p>「任务自动完成」不单独设动作码（2026-10-04 评审裁定，15 §E10）：它是定稿的后果，且无条件写审计
   * 会造出假的"完成"记录（已 COMPLETED 时重跑完单 SQL 影响 0 行）。
   */
  TASK_SUPPLIER_FINALIZE,
  /**
   * 管理员解锁重评（R5，M5 W3）：detail 记轮次、解锁前总分/等级与**意见摘要**（可选输入，按 06 §2.8 截断）。
   *
   * <p>它是「生效结果被撤销」的唯一直接证据：不单独记录被删的维度快照（行数多、且快照可用定稿审计复核）。
   */
  TASK_SUPPLIER_UNLOCK,
  /** M6：生成开放 API Token（detail 记名称/prefix，**不记明文与哈希**）。 */
  TOKEN_CREATE,
  /** M6：停用开放 API Token（detail 记名称与状态变化，不记明文与哈希）。 */
  TOKEN_DISABLE,
  // ---- 整改闭环（V1004；动作码只增不改）----
  CORRECTIVE_ACTION_CREATE,
  CORRECTIVE_ACTION_UPDATE,
  CORRECTIVE_ACTION_START,
  CORRECTIVE_ACTION_SUBMIT,
  CORRECTIVE_ACTION_VERIFY,
  CORRECTIVE_ACTION_CANCEL,
  ACCESS_DENIED
}
