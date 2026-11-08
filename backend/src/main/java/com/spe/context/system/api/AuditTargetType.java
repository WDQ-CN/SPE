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
  TOKEN,
  /** 整改任务（V1004；目标 id = corrective_action.id）。 */
  CORRECTIVE_ACTION,
  /** E1 主数据（V1006；目标 id = 各自主键）。 */
  COMPANY,
  PLANT,
  WAREHOUSE,
  /** 仓库库位（V1013）。 */
  WAREHOUSE_LOCATION,
  ITEM,
  CUSTOMER,
  PRICE_LIST,
  TAX_CODE,
  GL_ACCOUNT,
  ACCOUNT_MAPPING,
  /** E1 P2P（V1008）：请购单 / 采购订单 / 收货单。 */
  PURCHASE_REQUISITION,
  PURCHASE_ORDER,
  GOODS_RECEIPT,
  /** W3 库存移动（目标 id = inventory_movement.id）。 */
  INVENTORY_MOVEMENT,
  /** W3 采购退货（目标 id = purchase_return.id）。 */
  PURCHASE_RETURN,
  /** W3 库存批次（V1013；目标 id = inventory_lot.id）。 */
  INVENTORY_LOT,
  /** E2 库存运营（V1016）：预留/冻结/调拨/盘点/库位容量。 */
  INVENTORY_RESERVATION,
  INVENTORY_FREEZE,
  INVENTORY_TRANSFER,
  INVENTORY_COUNT,
  LOCATION_CAPACITY,
  SOURCING_RFQ,
  SUPPLIER_QUOTE,
  SOURCING_AWARD,
  PURCHASE_CONTRACT,
  SUPPLIER_PRICE,
  SUPPLIER_PORTAL_INVITATION,
  SALES_QUOTE,
  SALES_ORDER,
  SALES_SHIPMENT,
  SALES_INVOICE,
  CUSTOMER_RECEIPT,
  SALES_RETURN,
  BOM,
  WORK_ORDER,
  INSPECTION_PLAN,
  INSPECTION_ORDER,
  NCR,
  CAPA,
  SUPPLIER_CERTIFICATE,
  FIXED_ASSET,
  PROJECT,
  PROJECT_BUDGET,
  TIMESHEET,
  WORKFLOW_DEFINITION,
  WORKFLOW_INSTANCE,
  WORKFLOW_TASK,
  EVENT_SUBSCRIPTION,
  OUTBOX_EVENT,
  SOD_RULE,
  SOD_EXCEPTION,
  REPORT_DEFINITION,
  REPORT_RUN,
  /** 供应商门户账号（V1014；目标 id = supplier_portal_user.id）。 */
  SUPPLIER_PORTAL_USER,
  /** 竞价事件（V1014；目标 id = auction_event.id）。 */
  AUCTION,
  AUCTION_RESULT,
  /** E1 发票与付款（W4）。 */
  SUPPLIER_INVOICE,
  PAYMENT,
  /** E1 最小财务（W5）：会计凭证。 */
  JOURNAL_ENTRY
}
