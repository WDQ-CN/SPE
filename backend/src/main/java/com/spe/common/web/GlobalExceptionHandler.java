package com.spe.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 全局异常处理器（09 D8）：把异常统一转成 {@link Result} + 对应 HTTP 状态。
 *
 * <p>继承 {@code ResponseEntityExceptionHandler} 以保留 Spring 对 404/405/415 等标准异常的处理，
 * 自定义处理器按「更具体者优先」生效。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  /** 业务异常：按错误码携带的 HTTP 状态返回。2xxx 由前端表单回显，不额外包装文案。 */
  @ExceptionHandler(BizException.class)
  public ResponseEntity<Result<Void>> handleBizException(BizException ex) {
    ErrorCode errorCode = ex.errorCode();
    if (errorCode == ErrorCode.INTERNAL_ERROR) {
      log.error("业务异常（系统段）：{}", ex.getMessage(), ex);
    } else {
      log.warn("业务异常：code={}，message={}", errorCode.code(), ex.getMessage());
    }
    return ResponseEntity.status(errorCode.httpStatus())
        .body(Result.fail(errorCode.code(), ex.getMessage()));
  }

  /** 越权：方法级鉴权在 Controller 内抛出时由此兜底（过滤器链上的 403 见 AccessDeniedHandler）。 */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex) {
    log.warn("越权访问：{}", ex.getMessage());
    return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus()).body(Result.fail(ErrorCode.FORBIDDEN));
  }

  /** 兜底：5xxx，日志带堆栈（06 §2.8）。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("未预期异常：{} {}", request.getMethod(), request.getRequestURI(), ex);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus()).body(Result.fail(ErrorCode.INTERNAL_ERROR));
  }

  /** 参数校验失败：拼接字段错误，按 2xxx 返回，供表单回显。 */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
        .collect(Collectors.joining("；"));
    log.warn("参数校验失败：{}", message);
    return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
        .body(Result.fail(ErrorCode.BAD_REQUEST.code(), message.isEmpty() ? ErrorCode.BAD_REQUEST.message() : message));
  }
}
