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
  IMPORT_FILE_TOO_LARGE(2030, "上传文件超过大小限制", HttpStatus.PAYLOAD_TOO_LARGE),
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
  CONCURRENT_CONFLICT(3001, "数据已被他人改动，请刷新后重试", HttpStatus.CONFLICT),
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
