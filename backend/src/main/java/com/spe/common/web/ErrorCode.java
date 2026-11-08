package com.spe.common.web;

import org.springframework.http.HttpStatus;

/**
 * 错误码分段（02 §4 / 06 §2.6）：1xxx 认证、2xxx 参数、3xxx 状态冲突、4xxx 权限、5xxx 系统。
 *
 * <p>P0 预置基线码（09 D8），P1 按分段追加（10 §E2/E11）；前端按千位分段决定处理方式（06 §3.3）。
 */
public enum ErrorCode {
  UNAUTHORIZED(1001, "未登录或登录已过期", HttpStatus.UNAUTHORIZED),
  /** 登录失败：账号不存在、口令错误、账号已停用共用一码，避免账号枚举（10 §E12）。 */
  LOGIN_FAILED(1002, "账号或密码错误", HttpStatus.UNAUTHORIZED),
  /**
   * 登录失败次数超限后的临时锁定（03 §16 的 SEC-5）。
   *
   * <p>1xxx 段里的**第二个 HTTP 状态**（429 而不是 401）：语义上它不是「凭据错误」而是「请稍后再试」，
   * 前端也不能按 1xxx 默认分支处理（那会清 token 并跳登录页）。分段允许集合见 `ErrorCodeTest`。
   */
  LOGIN_TOO_MANY_ATTEMPTS(1003, "尝试过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),
  /** 门户出价频控：与登录共用 429 语义，前端提示稍后再试。 */
  PORTAL_RATE_LIMITED(1004, "操作过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),
  BAD_REQUEST(2001, "请求参数有误", HttpStatus.BAD_REQUEST),
  USERNAME_EXISTS(2002, "账号已存在", HttpStatus.BAD_REQUEST),
  PASSWORD_TOO_WEAK(2003, "新口令长度不得少于 8 位", HttpStatus.BAD_REQUEST),
  PASSWORD_MISMATCH(2004, "原口令不正确", HttpStatus.BAD_REQUEST),
  THRESHOLD_INVALID(2005, "阈值需满足 0 ≤ D ≤ C ≤ B ≤ A ≤ 100", HttpStatus.BAD_REQUEST),
  USER_NOT_FOUND(2006, "用户不存在", HttpStatus.BAD_REQUEST),
  /** 供应商编码唯一（01 S2）：DB 唯一键 `uk_supplier_code` 是最后防线，应用层前置校验只是体验（06 §4）。 */
  SUPPLIER_CODE_EXISTS(2007, "供应商编码已存在", HttpStatus.BAD_REQUEST),
  SUPPLIER_NOT_FOUND(2008, "供应商不存在", HttpStatus.BAD_REQUEST),
  // ---- P2（12 §E6）：模板与模板版本。全部落 2xxx/400，ErrorCodeTest 会卡分段 ----
  TEMPLATE_NOT_FOUND(2009, "模板不存在", HttpStatus.BAD_REQUEST),
  TEMPLATE_VERSION_NOT_FOUND(2010, "模板版本不存在", HttpStatus.BAD_REQUEST),
  /** 维度权重合计或某维度内指标权重合计 ≠ 100；明细拼在 message 里（哪一项、实际多少）。 */
  TEMPLATE_WEIGHT_INVALID(2011, "权重合计必须为 100", HttpStatus.BAD_REQUEST),
  /** 某维度一个指标都没有（01 T4「每维度至少 1 个指标」）；模板一个维度都没有时也用它。 */
  TEMPLATE_DIMENSION_EMPTY(2012, "每个维度至少需要一个指标", HttpStatus.BAD_REQUEST),
  TEMPLATE_TARGET_INVALID(2013, "TARGET 指标的目标值必须大于 0", HttpStatus.BAD_REQUEST),
  TEMPLATE_INDICATOR_CODE_DUPLICATE(2014, "同一版本内指标编码不可重复", HttpStatus.BAD_REQUEST),
  /** 叶子端点（改/删维度）的目标不存在。与 2006/2008 同口径，不能省——否则只能错用 2009 或漏成 5xxx。 */
  TEMPLATE_DIMENSION_NOT_FOUND(2015, "维度不存在", HttpStatus.BAD_REQUEST),
  TEMPLATE_INDICATOR_NOT_FOUND(2016, "指标不存在", HttpStatus.BAD_REQUEST),

  /**
   * 单行取值或长度不合法（权重越界/精度超限、必填为空、字段超长）。
   *
   * <p>与 {@link #TEMPLATE_WEIGHT_INVALID} 分开：那个讲的是「**合计**不等于 100」这个跨行业务规则，
   * 这个讲的是「**某一行**的取值本身填错了」。混用会让用户看到"权重合计有问题"而实际是某个字段超长。
   */
  TEMPLATE_VALUE_INVALID(2017, "字段取值或长度不合法", HttpStatus.BAD_REQUEST),
  // ---- P3（13 §E20）：任务 / 评分单。分段口径：状态冲突用 3001、状态本就不允许用 2020、横向越权用 4xxx ----
  TASK_NOT_FOUND(2018, "评估任务不存在", HttpStatus.BAD_REQUEST),
  /** 01 J4：同一供应商同一周期只允许一个非取消任务。⚠️ 该约束**没有数据库兜底**（13 §E10）。 */
  TASK_PERIOD_DUPLICATE(2019, "该供应商在本周期已有评估任务", HttpStatus.BAD_REQUEST),
  /** 状态**本就不允许**该操作（如把已发布任务当草稿改）；并发下别人先改了则走 3001（13 §E20 的口径）。 */
  TASK_STATUS_INVALID(2020, "任务当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  TASK_SUPPLIER_EMPTY(2021, "请至少选择一个供应商，且审批链至少一级", HttpStatus.BAD_REQUEST),
  SHEET_NOT_FOUND(2022, "评分单不存在", HttpStatus.BAD_REQUEST),
  /** 指定的人员不存在、已停用或角色不符（审批链成员与改派对象共用；01 §7 的两个补充边界）。 */
  USER_NOT_ELIGIBLE(2023, "指定的人员不存在、已停用或角色不符", HttpStatus.BAD_REQUEST),
  SHEET_SCORES_INCOMPLETE(2024, "该维度还有指标未填写", HttpStatus.BAD_REQUEST),
  SCORE_VALUE_INVALID(2025, "录入值超出允许范围", HttpStatus.BAD_REQUEST),
  /** 上传的不是可解析的 xlsx / 表头缺失（文件级失败，01 E3）。 */
  IMPORT_FILE_INVALID(2026, "文件无法解析为 xlsx，请重新下载导入模板", HttpStatus.BAD_REQUEST),
  /** 逐格校验失败：message 里带**行列定位**，可多条（01 E3）。有它 ⇒ **整批不写**，避免半截批次。 */
  IMPORT_CELL_INVALID(2027, "导入内容有误", HttpStatus.BAD_REQUEST),
  /** 评估单不存在（P4 审批/报告/定稿的入口校验，15 §E9）。 */
  TASK_SUPPLIER_NOT_FOUND(2028, "评估单不存在", HttpStatus.BAD_REQUEST),
  /** 驳回必须带意见（01 FR-P1；15 §E9）。 */
  APPROVAL_COMMENT_REQUIRED(2029, "驳回时必须填写意见", HttpStatus.BAD_REQUEST),

  /**
   * 上传体积超过 `spring.servlet.multipart.max-file-size`（03 §38 的 P1-7，裁决点 4 采纳）。
   *
   * <p>不复用 2026（"文件无法解析为 xlsx"）：那个文案会说谎——文件是好的，只是太大。
   * ⚠️ 它是 2xxx 段里**第一个**带 413 的码：`ErrorCodeTest` 的分段合法集合因此从 `{400}` 扩成
   * `{400, 413}`，`06 §2.6` 的分段表同步（1xxx 早有 401+429 的同族先例）。
   */
  IMPORT_FILE_TOO_LARGE(2030, "上传文件超过大小限制", HttpStatus.CONTENT_TOO_LARGE),
  /**
   * 模板版本不属于所选模板（`21 §3.W1-2`）。
   *
   * <p>不复用 2010（"模板版本不存在或未发布"）：版本是好的，只是不属于这个模板——两者都是前端参数错误，
   * 但文案要说真话（与 2030 不复用 2026 同一条原则）。
   */
  TEMPLATE_VERSION_MISMATCH(2031, "模板版本不属于所选模板", HttpStatus.BAD_REQUEST),
  /** 预警不存在（M5 W3 处理入口，22 §2 D1）。 */
  ALERT_NOT_FOUND(2032, "预警不存在", HttpStatus.BAD_REQUEST),
  /**
   * 预警当前状态不允许该操作（M5 W3）：只有 UNREAD 能处理；已处理/已取代再点 → 本码。
   *
   * <p>守卫 UPDATE 0 行后**复读一次**：行不在了 ⇒ 2032；已是 HANDLED/SUPERSEDED ⇒ 本码（并发后到者
   * 同样落到这里——alert 没有 version 列，状态机本身就是唯一判据，06 §2.4 的"复读定夺"）。
   */
  ALERT_STATUS_INVALID(2033, "预警当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  /** 预警规则不合法（M5 SM3）：阈值/周期数/降幅越界或顺序不成立（22 §2 D9）。 */
  ALERT_RULE_INVALID(2034, "预警规则不合法", HttpStatus.BAD_REQUEST),
  /** 开放 API Token 不存在（M6 Token 管理/停用入口）。 */
  API_TOKEN_NOT_FOUND(2035, "API Token 不存在", HttpStatus.BAD_REQUEST),
  /** Token 当前状态不允许该操作（M6：已停用再停用；停用不可逆）。 */
  API_TOKEN_STATUS_INVALID(2036, "API Token 当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  /** 开放 API 上报的供应商不在指定任务中（M6；与 2028「评估单不存在」区分）。 */
  SUPPLIER_NOT_IN_TASK(2037, "供应商不在该任务中", HttpStatus.BAD_REQUEST),
  /** 整改任务不存在（W1/V1004）。 */
  CORRECTIVE_ACTION_NOT_FOUND(2038, "整改任务不存在", HttpStatus.BAD_REQUEST),
  /** 整改任务当前状态不允许该动作（状态机拒绝；W1/V1004）。 */
  CORRECTIVE_ACTION_STATUS_INVALID(2039, "整改任务当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  /** 预警缺少结构化口径或已有整改，不能创建整改（D3/D8）。 */
  ALERT_NOT_ACTIONABLE(2040, "该预警不能创建整改", HttpStatus.BAD_REQUEST),
  /** 整改负责人不在允许集合（只允许在职 EVALUATOR/MANAGER）。 */
  CORRECTIVE_ACTION_OWNER_INVALID(2041, "整改负责人不合法", HttpStatus.BAD_REQUEST),
  // ---- E1 主数据（docs/erp/48、49）：2100 段；2xxx 默认 400 ----
  COMPANY_NOT_FOUND(2101, "公司不存在", HttpStatus.BAD_REQUEST),
  COMPANY_CODE_EXISTS(2102, "公司编码已存在", HttpStatus.BAD_REQUEST),
  PLANT_NOT_FOUND(2103, "工厂不存在", HttpStatus.BAD_REQUEST),
  PLANT_CODE_EXISTS(2104, "工厂编码已存在", HttpStatus.BAD_REQUEST),
  WAREHOUSE_NOT_FOUND(2105, "仓库不存在", HttpStatus.BAD_REQUEST),
  WAREHOUSE_CODE_EXISTS(2106, "仓库编码已存在", HttpStatus.BAD_REQUEST),
  ITEM_NOT_FOUND(2107, "物料不存在", HttpStatus.BAD_REQUEST),
  ITEM_CODE_EXISTS(2108, "物料编码已存在", HttpStatus.BAD_REQUEST),
  ITEM_UOM_INVALID(2109, "计量单位换算不合法", HttpStatus.BAD_REQUEST),
  MASTER_DATA_HIERARCHY_INVALID(2110, "上级组织不存在或已停用", HttpStatus.BAD_REQUEST),
  ITEM_UOM_NOT_FOUND(2111, "计量单位换算不存在", HttpStatus.BAD_REQUEST),
  MASTER_DATA_STATUS_INVALID(2112, "主数据当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  CUSTOMER_NOT_FOUND(2113, "客户不存在", HttpStatus.BAD_REQUEST),
  CUSTOMER_CODE_EXISTS(2114, "客户编码已存在", HttpStatus.BAD_REQUEST),
  PRICE_LIST_NOT_FOUND(2115, "价格表不存在", HttpStatus.BAD_REQUEST),
  PRICE_LIST_CODE_EXISTS(2116, "价格表编码已存在", HttpStatus.BAD_REQUEST),
  PRICE_LINE_INVALID(2117, "价格表行不合法", HttpStatus.BAD_REQUEST),
  PRICE_LINE_NOT_FOUND(2118, "价格表行不存在", HttpStatus.BAD_REQUEST),
  TAX_CODE_NOT_FOUND(2119, "税码不存在", HttpStatus.BAD_REQUEST),
  TAX_CODE_EXISTS(2120, "税码已存在", HttpStatus.BAD_REQUEST),
  GL_ACCOUNT_NOT_FOUND(2121, "会计科目不存在", HttpStatus.BAD_REQUEST),
  GL_ACCOUNT_CODE_EXISTS(2122, "会计科目编码已存在", HttpStatus.BAD_REQUEST),
  ACCOUNT_MAPPING_NOT_FOUND(2123, "科目映射不存在", HttpStatus.BAD_REQUEST),
  ACCOUNT_MAPPING_INVALID(2124, "科目映射不合法", HttpStatus.BAD_REQUEST),
  ACCOUNT_MAPPING_EXISTS(2125, "同一事件/维度/生效日的科目映射已存在", HttpStatus.BAD_REQUEST),
  WAREHOUSE_LOCATION_NOT_FOUND(2126, "库位不存在", HttpStatus.BAD_REQUEST),
  WAREHOUSE_LOCATION_CODE_EXISTS(2127, "库位编码已存在", HttpStatus.BAD_REQUEST),
  WAREHOUSE_LOCATION_STATUS_INVALID(2128, "库位当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  // ---- E1 P2P（docs/erp/32、49、52）：2200 段 ----
  PURCHASE_REQUISITION_NOT_FOUND(2201, "请购单不存在", HttpStatus.BAD_REQUEST),
  /** PO 单价高于有效采购价目（容差 1% 或 0.01，取大）。 */
  PURCHASE_ORDER_PRICE_EXCEEDS_REFERENCE(2202, "采购单价高于有效价目", HttpStatus.BAD_REQUEST),
  PURCHASE_REQUISITION_STATUS_INVALID(2203, "请购单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  /** 收货数量超过 PO 行未收数量；E1 不做超收容差（W3 再扩展）。 */
  GOODS_RECEIPT_QTY_EXCEEDS_OPEN(2204, "收货数量超过订单未收数量", HttpStatus.BAD_REQUEST),
  /** 同一供应商的发票号唯一（P2P-006）；重复直接拒绝。 */
  SUPPLIER_INVOICE_DUPLICATE(2205, "该供应商的发票号已存在", HttpStatus.BAD_REQUEST),
  /** 发票处于匹配异常，批准必须填写处理原因。 */
  SUPPLIER_INVOICE_MISMATCH(2206, "发票匹配异常，批准时必须填写原因", HttpStatus.BAD_REQUEST),
  PAYMENT_BLOCKED(2207, "付款条件不满足，无法付款", HttpStatus.BAD_REQUEST),
  PURCHASE_ORDER_NOT_FOUND(2208, "采购订单不存在", HttpStatus.BAD_REQUEST),
  PURCHASE_ORDER_STATUS_INVALID(2209, "采购订单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  PURCHASE_ORDER_LINE_INVALID(2210, "采购订单行不合法", HttpStatus.BAD_REQUEST),
  PURCHASE_ORDER_SUPPLIER_INVALID(2211, "供应商不存在或已停用", HttpStatus.BAD_REQUEST),
  PURCHASE_REQUISITION_SOURCE_INVALID(2212, "来源请购单不存在、未审批或已转单", HttpStatus.BAD_REQUEST),
  GOODS_RECEIPT_NOT_FOUND(2213, "收货单不存在", HttpStatus.BAD_REQUEST),
  GOODS_RECEIPT_LINE_INVALID(2214, "收货单行不合法", HttpStatus.BAD_REQUEST),
  GOODS_RECEIPT_WAREHOUSE_INVALID(2215, "仓库不存在、已停用或不属于订单工厂", HttpStatus.BAD_REQUEST),
  GOODS_RECEIPT_PO_NOT_RECEIVABLE(2216, "采购订单当前状态不可收货", HttpStatus.BAD_REQUEST),
  /** 金额没有命中任何审批规则——配置缺陷，不能猜一个审批级别放行。 */
  APPROVAL_RULE_NOT_CONFIGURED(2217, "未配置匹配的审批规则", HttpStatus.BAD_REQUEST),
  /** E1 不做多币种：单据币种必须等于公司本位币。 */
  PROCUREMENT_CURRENCY_INVALID(2218, "币种必须与公司本位币一致", HttpStatus.BAD_REQUEST),
  SUPPLIER_INVOICE_NOT_FOUND(2219, "供应商发票不存在", HttpStatus.BAD_REQUEST),
  SUPPLIER_INVOICE_STATUS_INVALID(2220, "供应商发票当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  SUPPLIER_INVOICE_LINE_INVALID(2221, "供应商发票行不合法", HttpStatus.BAD_REQUEST),
  PAYMENT_NOT_FOUND(2222, "付款申请不存在", HttpStatus.BAD_REQUEST),
  PAYMENT_STATUS_INVALID(2223, "付款申请当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  PAYMENT_AMOUNT_INVALID(2224, "付款金额必须大于 0", HttpStatus.BAD_REQUEST),
  PAYMENT_EXCEEDS_INVOICE(2225, "付款金额超过发票未付金额", HttpStatus.BAD_REQUEST),
  PURCHASE_REQUISITION_LINE_INVALID(2226, "请购单行不合法", HttpStatus.BAD_REQUEST),
  /** 匹配容差未配置——不能默认 0 容差，否则所有发票都会变成异常。 */
  MATCH_TOLERANCE_NOT_CONFIGURED(2227, "未配置三单匹配容差", HttpStatus.BAD_REQUEST),
  /** 并发批准两张匹配发票时，累计开票量超过已收 + 数量容差。 */
  SUPPLIER_INVOICE_QTY_EXCEEDS_RECEIVED(2228, "累计开票数量超过已收数量容差", HttpStatus.BAD_REQUEST),
  /** 已开票的收货不能冲销：冲销后已收量会低于已开票量。 */
  GOODS_RECEIPT_INVOICED(2229, "已开票的收货不能冲销", HttpStatus.BAD_REQUEST),
  GOODS_RECEIPT_STATUS_INVALID(2230, "收货单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  PURCHASE_RETURN_NOT_FOUND(2231, "采购退货单不存在", HttpStatus.BAD_REQUEST),
  /** 退货数量超过「已收 - 已退」；没有收货不能退货。 */
  PURCHASE_RETURN_QTY_EXCEEDS_RECEIVED(2232, "退货数量超过可退数量", HttpStatus.BAD_REQUEST),
  PURCHASE_RETURN_STATUS_INVALID(2233, "采购退货单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  RFQ_STATUS_INVALID(2234, "询价单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  /** 密封报价在截止前不可查看金额，或报价不属于当前供应商。 */
  QUOTE_SEALED(2236, "密封报价尚未到可查看时间", HttpStatus.BAD_REQUEST),
  AWARD_QTY_EXCEEDS_QUOTE(2237, "定标数量超过报价可供量或 RFQ 剩余量", HttpStatus.BAD_REQUEST),
  AWARD_ALREADY_CONVERTED(2240, "定标已转采购订单或合同，不能重复转单", HttpStatus.BAD_REQUEST),
  CONTRACT_LIMIT_EXCEEDED(2242, "合同数量、金额或有效期超限", HttpStatus.BAD_REQUEST),
  CONTRACT_STATUS_INVALID(2243, "合同当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  SUPPLIER_PRICE_INVALID(2244, "供应商价格不在有效价格库或合同内", HttpStatus.BAD_REQUEST),
  AUCTION_CLOSED(2235, "竞价已截止", HttpStatus.BAD_REQUEST),
  AUCTION_STATUS_INVALID(2238, "竞价当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  /** 未达到最小降幅或高于起拍价（反向竞价）。 */
  AUCTION_BID_TOO_HIGH(2239, "报价未达到最小降幅或高于起拍价", HttpStatus.BAD_REQUEST),
  AUCTION_NOT_INVITED(2241, "未被邀请参与该竞价", HttpStatus.BAD_REQUEST),
  AUCTION_CURRENCY_INVALID(2245, "竞价币种不匹配", HttpStatus.BAD_REQUEST),
  AUCTION_IDEMPOTENCY_CONFLICT(2247, "幂等键已被不同内容使用", HttpStatus.BAD_REQUEST),
  AUCTION_BID_INVALID(2248, "出价金额不合法", HttpStatus.BAD_REQUEST),
  AUCTION_NOT_STARTED(2249, "竞价尚未开始", HttpStatus.BAD_REQUEST),
  AUCTION_NOT_FOUND(2251, "竞价不存在", HttpStatus.BAD_REQUEST),
  AUCTION_RESULT_STATUS_INVALID(2255, "竞价结果当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  SUPPLIER_PORTAL_USER_NOT_FOUND(2252, "供应商门户账号不存在", HttpStatus.BAD_REQUEST),
  SUPPLIER_PORTAL_LOGIN_EXISTS(2253, "门户登录名已存在", HttpStatus.BAD_REQUEST),
  SUPPLIER_PORTAL_USER_STATUS_INVALID(2254, "供应商门户账号状态不允许该操作", HttpStatus.BAD_REQUEST),
  /** 邀请 Token 不存在、已使用、已撤销或已过期，统一拒绝，避免枚举。 */
  SUPPLIER_PORTAL_INVITATION_INVALID(4007, "供应商门户邀请无效或已过期", HttpStatus.FORBIDDEN),
  /** SSE 短令牌不存在、已使用或已过期。 */
  SUPPLIER_PORTAL_STREAM_TOKEN_INVALID(4008, "竞价事件流令牌无效或已过期", HttpStatus.FORBIDDEN),
  // ---- E3 销售（V1021）：2500 段 ----
  SALES_QUOTE_NOT_FOUND(2501, "销售报价不存在", HttpStatus.BAD_REQUEST),
  SALES_QUOTE_STATUS_INVALID(2502, "销售报价当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  SALES_ORDER_NOT_FOUND(2503, "销售订单不存在", HttpStatus.BAD_REQUEST),
  SALES_ORDER_STATUS_INVALID(2504, "销售订单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  SALES_CREDIT_EXCEEDED(2505, "客户信用额度不足", HttpStatus.BAD_REQUEST),
  SALES_ORDER_LINE_INVALID(2506, "销售订单行不合法", HttpStatus.BAD_REQUEST),
  SALES_SHIPMENT_NOT_FOUND(2507, "销售发货单不存在", HttpStatus.BAD_REQUEST),
  SALES_SHIPMENT_LINE_INVALID(2508, "销售发货行不合法", HttpStatus.BAD_REQUEST),
  SALES_INVOICE_NOT_FOUND(2509, "销售发票不存在", HttpStatus.BAD_REQUEST),
  SALES_INVOICE_LINE_INVALID(2510, "销售发票行不合法或超过可开票数量", HttpStatus.BAD_REQUEST),
  CUSTOMER_RECEIPT_NOT_FOUND(2511, "客户收款不存在", HttpStatus.BAD_REQUEST),
  RECEIPT_ALLOCATION_INVALID(2512, "收款核销金额或发票状态不合法", HttpStatus.BAD_REQUEST),
  SALES_RETURN_NOT_FOUND(2513, "销售退货单不存在", HttpStatus.BAD_REQUEST),
  SALES_RETURN_STATUS_INVALID(2514, "销售退货单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  // ---- E6 报表（V1034）：2290 段 ----
  REPORT_DEFINITION_NOT_FOUND(2290, "报表不存在", HttpStatus.BAD_REQUEST),
  REPORT_RUN_NOT_FOUND(2291, "报表运行记录不存在", HttpStatus.BAD_REQUEST),
  // ---- E6 治理（V1032）：2280 段 ----
  SOD_RULE_NOT_FOUND(2280, "SoD 规则不存在", HttpStatus.BAD_REQUEST),
  SOD_RULE_INVALID(2281, "SoD 规则不合法", HttpStatus.BAD_REQUEST),
  SOD_EXCEPTION_NOT_FOUND(2282, "SoD 例外不存在", HttpStatus.BAD_REQUEST),
  SOD_EXCEPTION_STATUS_INVALID(2283, "SoD 例外当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  // ---- E6 集成（V1031）：2270 段 ----
  EVENT_SUBSCRIPTION_NOT_FOUND(2270, "事件订阅不存在", HttpStatus.BAD_REQUEST),
  EVENT_SUBSCRIPTION_STATUS_INVALID(2271, "事件订阅当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  OUTBOX_EVENT_NOT_FOUND(2272, "Outbox 事件不存在", HttpStatus.BAD_REQUEST),
  WEBHOOK_SUBSCRIPTION_INVALID(2273, "Webhook 订阅参数不合法", HttpStatus.BAD_REQUEST),
  // ---- E6 工作流（V1030）：2256 段 ----
  WORKFLOW_DEFINITION_NOT_FOUND(2256, "工作流定义不存在", HttpStatus.BAD_REQUEST),
  WORKFLOW_DEFINITION_STATUS_INVALID(2257, "工作流定义当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  WORKFLOW_INSTANCE_NOT_FOUND(2258, "工作流实例不存在", HttpStatus.BAD_REQUEST),
  WORKFLOW_TASK_NOT_FOUND(2259, "工作流任务不存在", HttpStatus.BAD_REQUEST),
  WORKFLOW_TASK_STATUS_INVALID(2260, "工作流任务当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  WORKFLOW_APPROVER_ROLE_INVALID(2261, "当前用户不具备该节点审批角色", HttpStatus.BAD_REQUEST),
  WORKFLOW_DEFINITION_MISSING(2262, "没有匹配的有效工作流定义", HttpStatus.BAD_REQUEST),
  // ---- E5 项目（V1029）：2900 段 ----
  PROJECT_NOT_FOUND(2901, "项目不存在", HttpStatus.BAD_REQUEST),
  PROJECT_STATUS_INVALID(2902, "项目当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  PROJECT_WBS_INVALID(2903, "项目 WBS 不存在或不属于该项目", HttpStatus.BAD_REQUEST),
  PROJECT_BUDGET_EXCEEDED(2904, "项目预算不足", HttpStatus.BAD_REQUEST),
  TIMESHEET_NOT_FOUND(2905, "工时单不存在", HttpStatus.BAD_REQUEST),
  TIMESHEET_STATUS_INVALID(2906, "工时单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  // ---- E5 资产（V1028）：2800 段 ----
  ASSET_NOT_FOUND(2801, "资产不存在", HttpStatus.BAD_REQUEST),
  ASSET_STATUS_INVALID(2802, "资产当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  ASSET_DEPRECIATION_INVALID(2803, "折旧期间或金额不合法", HttpStatus.BAD_REQUEST),
  ASSET_DISPOSAL_INVALID(2804, "资产处置参数不合法", HttpStatus.BAD_REQUEST),
  // ---- E5 质量（V1027）：2700 段 ----
  INSPECTION_PLAN_NOT_FOUND(2701, "检验计划不存在", HttpStatus.BAD_REQUEST),
  INSPECTION_PLAN_STATUS_INVALID(2702, "检验计划当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  INSPECTION_NOT_FOUND(2703, "检验单不存在", HttpStatus.BAD_REQUEST),
  INSPECTION_STATUS_INVALID(2704, "检验单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  INSPECTION_RESULT_INVALID(2705, "检验结果不合法", HttpStatus.BAD_REQUEST),
  NCR_NOT_FOUND(2706, "不合格品报告不存在", HttpStatus.BAD_REQUEST),
  NCR_STATUS_INVALID(2707, "不合格品报告当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  CAPA_NOT_FOUND(2708, "CAPA 不存在", HttpStatus.BAD_REQUEST),
  CAPA_STATUS_INVALID(2709, "CAPA 当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  SUPPLIER_CERTIFICATE_INVALID(2710, "供应商证书不合法", HttpStatus.BAD_REQUEST),
  // ---- E4 制造（V1026）：2600 段 ----
  BOM_NOT_FOUND(2601, "BOM 不存在", HttpStatus.BAD_REQUEST),
  BOM_STATUS_INVALID(2602, "BOM 当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  WORK_ORDER_NOT_FOUND(2603, "生产工单不存在", HttpStatus.BAD_REQUEST),
  WORK_ORDER_STATUS_INVALID(2604, "生产工单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  WORK_ORDER_ISSUE_EXCEEDS(2605, "领料数量超过工单需求", HttpStatus.BAD_REQUEST),
  WORK_ORDER_RECEIPT_EXCEEDS(2606, "完工数量超过工单计划/剩余数量", HttpStatus.BAD_REQUEST),
  WORK_ORDER_COMPONENT_INVALID(2607, "工单组件不合法", HttpStatus.BAD_REQUEST),
  // ---- E1 库存（V1011）：2300 段 ----
  /** 冲销会让库存变为负数；E1 不允许负库存。 */
  NEGATIVE_STOCK(2301, "库存不足，不能冲销", HttpStatus.BAD_REQUEST),
  INVENTORY_LOT_REQUIRED(2302, "批次管理物料必须指定批次", HttpStatus.BAD_REQUEST),
  INVENTORY_LOT_NOT_FOUND(2303, "批次不存在", HttpStatus.BAD_REQUEST),
  INVENTORY_LOT_STATUS_INVALID(2304, "批次当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  INVENTORY_LOT_DATE_INVALID(2305, "批次有效期不能早于生产日期", HttpStatus.BAD_REQUEST),
  INVENTORY_SERIAL_REQUIRED(2306, "序列管理物料必须提供与数量一致的序列号", HttpStatus.BAD_REQUEST),
  INVENTORY_SERIAL_DUPLICATE(2307, "序列号重复或已存在", HttpStatus.BAD_REQUEST),
  INVENTORY_SERIAL_STATUS_INVALID(2308, "序列号当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  INVENTORY_TRANSFER_STATUS_INVALID(2309, "调拨单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  INVENTORY_RESERVATION_INSUFFICIENT(2310, "可用库存不足，无法预留", HttpStatus.BAD_REQUEST),
  INVENTORY_FREEZE_INSUFFICIENT(2311, "可用库存不足，无法冻结", HttpStatus.BAD_REQUEST),
  INVENTORY_LOCATION_CAPACITY_EXCEEDED(2312, "目标库位容量超限", HttpStatus.BAD_REQUEST),
  INVENTORY_COUNT_STATUS_INVALID(2313, "盘点单当前状态不允许该操作", HttpStatus.BAD_REQUEST),
  FEFO_STOCK_INSUFFICIENT(2314, "可用库存不足，无法完成 FEFO 分配", HttpStatus.BAD_REQUEST),
  INVENTORY_VALUATION_NOT_CONFIGURED(2315, "估值方法或标准成本未配置", HttpStatus.BAD_REQUEST),
  // ---- E1 最小财务（docs/erp/34、49）：2400 段 ----
  JOURNAL_ENTRY_UNBALANCED(2401, "凭证借贷不平衡", HttpStatus.BAD_REQUEST),
  ACCOUNT_MAPPING_MISSING(2402, "未配置业务事件的科目映射", HttpStatus.BAD_REQUEST),
  // 重复过账是状态冲突，归 3xxx（2xxx 段默认 400，ErrorCodeTest 会卡分段）
  JOURNAL_ENTRY_NOT_FOUND(2404, "会计凭证不存在", HttpStatus.BAD_REQUEST),
  /** 科目不存在、已停用或不属于该公司——过账前必须拦住，不能把分录写到别家主体。 */
  FINANCE_ACCOUNT_INVALID(2405, "会计科目不存在、已停用或不属于该公司", HttpStatus.BAD_REQUEST),
  CONCURRENT_CONFLICT(3001, "数据已被他人改动，请刷新后重试", HttpStatus.CONFLICT),
  /** 同一业务单据已生成凭证；来源唯一键是最后防线，服务层在此之前已幂等跳过。 */
  DUPLICATE_POSTING(3004, "该业务单据已过账，不能重复过账", HttpStatus.CONFLICT),
  /** 不能停用当前登录账号（P2-8 / `26` §3.2：自停用一律禁止，误操作会让操作者自己失去唯一入口）。 */
  SELF_DISABLE_FORBIDDEN(3002, "不能停用当前登录账号", HttpStatus.CONFLICT),
  /** 系统必须至少保留一个启用中的管理员（P2-8：停用或降权最后一个 ENABLED ADMIN）。 */
  LAST_ENABLED_ADMIN(3003, "系统至少需要保留一个启用中的管理员", HttpStatus.CONFLICT),
  FORBIDDEN(4001, "无权限执行该操作", HttpStatus.FORBIDDEN),
  /**
   * 这张评分单不是指派给我的（01 §7 数据可见性 / 13 §E13 的第二层过滤）。
   *
   * <p>归 **4xxx 而不是 2xxx**（13 §E20 的裁决）：参数完全合法，是"你无权访问这一行"；放进 2xxx 会让前端
   * 按"表单回显"处理，把越权显示成"参数有误"。
   */
  SHEET_NOT_ACCESSIBLE(4002, "无权访问该评分单", HttpStatus.FORBIDDEN),
  /**
   * 这一单不在我当前的审批节点上（01 §3 经 2026-10-25 裁决：审批人只限 MANAGER/ADMIN ⇒
   * 角色决定入口、**节点才决定授权**）。
   *
   * <p>与 {@link #SHEET_NOT_ACCESSIBLE} 同族归 4xxx（15 §E9）：参数合法，是"这单不归你审"。
   */
  APPROVAL_NOT_YOUR_NODE(4003, "当前节点不属于你，无法审批", HttpStatus.FORBIDDEN),
  /** 职责分离（SoD）：申请人不能审批本人单据，PO 创建人不能收货。 */
  PROCUREMENT_SOD_VIOLATION(4004, "职责分离限制：不能审批或收取本人创建的单据", HttpStatus.FORBIDDEN),
  /** 审批矩阵要求更高角色（例如 >50,000 的 PR 需要 ADMIN）。 */
  APPROVAL_ROLE_INSUFFICIENT(4005, "当前金额需要更高角色的审批人", HttpStatus.FORBIDDEN),
  SUPPLIER_PORTAL_FORBIDDEN(4006, "供应商门户账号无权访问", HttpStatus.FORBIDDEN),
  INTERNAL_ERROR(5001, "系统异常，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR);

  private final int code;
  private final String message;
  private final HttpStatus httpStatus;

  ErrorCode(int code, String message, HttpStatus httpStatus) {
    this.code = code;
    this.message = message;
    this.httpStatus = httpStatus;
  }

  public int code() {
    return code;
  }

  public String message() {
    return message;
  }

  public HttpStatus httpStatus() {
    return httpStatus;
  }
}
