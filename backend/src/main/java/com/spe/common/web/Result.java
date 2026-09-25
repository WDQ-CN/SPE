package com.spe.common.web;

/**
 * 统一响应契约（09 D8）：{@code code = 0} 表示成功，其余按 1xxx 认证 / 2xxx 参数 / 3xxx 状态冲突 /
 * 4xxx 权限 / 5xxx 系统分段，分段定义见 {@link ErrorCode}。
 */
public record Result<T>(int code, String message, T data) {

  public static final int SUCCESS_CODE = 0;
  private static final String SUCCESS_MESSAGE = "成功";

  public static <T> Result<T> ok(T data) {
    return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
  }

  public static Result<Void> ok() {
    return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
  }

  public static <T> Result<T> fail(ErrorCode errorCode) {
    return new Result<>(errorCode.code(), errorCode.message(), null);
  }

  public static <T> Result<T> fail(int code, String message) {
    return new Result<>(code, message, null);
  }
}
