package com.spe.common.web;

/**
 * 业务异常（06 §2.6）：由全局异常处理器转 {@link Result}，不使用异常做流程控制。
 *
 * <p>继承 {@code RuntimeException} 以便状态守卫失败时的 {@code @Transactional} 整体回滚（06 §2.3）。
 */
public class BizException extends RuntimeException {

  private final ErrorCode errorCode;

  public BizException(ErrorCode errorCode) {
    super(errorCode.message());
    this.errorCode = errorCode;
  }

  public BizException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }
}
