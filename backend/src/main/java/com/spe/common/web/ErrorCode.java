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
  CONCURRENT_CONFLICT(3001, "数据已被他人改动，请刷新后重试", HttpStatus.CONFLICT),
  FORBIDDEN(4001, "无权限执行该操作", HttpStatus.FORBIDDEN),
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
