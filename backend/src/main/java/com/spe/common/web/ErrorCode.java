package com.spe.common.web;

import org.springframework.http.HttpStatus;

/**
 * 错误码分段（02 §4 / 06 §2.6）：1xxx 认证、2xxx 参数、3xxx 状态冲突、4xxx 权限、5xxx 系统。
 *
 * <p>P0 预置基线码（09 D8），后续按分段追加；前端按千位分段决定处理方式（06 §3.3）。
 */
public enum ErrorCode {
  UNAUTHORIZED(1001, "未登录或登录已过期", HttpStatus.UNAUTHORIZED),
  BAD_REQUEST(2001, "请求参数有误", HttpStatus.BAD_REQUEST),
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
