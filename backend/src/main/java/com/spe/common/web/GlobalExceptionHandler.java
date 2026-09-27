package com.spe.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

  /**
   * 锁等待超时 / 死锁 → 3001（与守卫 UPDATE 的 0 行同一个码）。
   *
   * <p>12 §E12 要求"两类并发冲突都翻成 3001"（唯一键冲突与锁冲突），但原先只有**插入/更新**那几条路径
   * 各自 `try/catch` 翻译，**锁定读**（`findByIdForUpdate` / `findByVersionIdForShare`）上的锁等待没有任何
   * 一层兜底——超过 `innodb_lock_wait_timeout` 或死锁时会落到 `Exception` 兜底变成 **5001「系统异常」**，
   * 用户看到"服务器错误"而不知道该刷新重试（03 §31.7.6 的 P2）。这里集中映射一次，覆盖全部写路径；
   * `CannotAcquireLockException` 是它的子类，一并覆盖。
   */
  @ExceptionHandler(PessimisticLockingFailureException.class)
  public ResponseEntity<Result<Void>> handleLockConflict(PessimisticLockingFailureException ex) {
    log.warn("并发冲突（锁等待/死锁）：{}", ex.getMessage());
    return ResponseEntity.status(ErrorCode.CONCURRENT_CONFLICT.httpStatus())
        .body(Result.fail(ErrorCode.CONCURRENT_CONFLICT));
  }

  /** 越权：方法级鉴权在 Controller 内抛出时由此兜底（过滤器链上的 403 见 AccessDeniedHandler）。 */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex) {
    log.warn("越权访问：{}", ex.getMessage());
    return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus()).body(Result.fail(ErrorCode.FORBIDDEN));
  }

  /**
   * 请求体无法反序列化：**非法枚举值**（如 `"type":"XXX"`）、坏 JSON、类型不匹配。
   *
   * <p>不覆写时它由父类 {@code ResponseEntityExceptionHandler} 处理，返回 400 但**响应体是 Spring
   * 默认结构**（不含 `Result` 的 `code`）——前端按 `code` 分流与表单回显都会失效，用户只看到"请求失败"。
   * 随 DTO 引入枚举字段（`IndicatorType`/`ScoreMode`）后这条路径更容易被走到，故归入 2xxx。
   *
   * <p>刻意**不回显** `ex.getMessage()`：它含目标类名与 JSON 路径，属于内部结构。
   */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    log.warn("请求体无法解析：{}", ex.getMessage());
    return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
        .body(Result.fail(ErrorCode.BAD_REQUEST.code(), "请求体格式不正确，或字段取值不在允许范围内"));
  }

  /** 兜底：5xxx，日志带堆栈（06 §2.8）。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error("未预期异常：{} {}", request.getMethod(), request.getRequestURI(), ex);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus()).body(Result.fail(ErrorCode.INTERNAL_ERROR));
  }

  /**
   * 把 Spring 的框架级异常统一包成 {@link Result}，**保留父类判定的 HTTP 状态**。
   *
   * <p>为什么必须逐个覆写：{@code ResponseEntityExceptionHandler} 默认产出 `ProblemDetail`
   * （`application/problem+json`），**里面没有 `code` 字段**。而前端 `app/http/index.ts` 按
   * `typeof result.code === 'number'` 分流——拿不到 code 就落到传输层兜底，用户看到的是一句
   * 「请求未被接受（400）」，2xxx 的表单回显与 3xxx/4xxx 的全局提示**同时失效**。
   *
   * <p>⚠️ 这是同一个坑的第二次：W3 只覆写了 {@link #handleHttpMessageNotReadable}（非法枚举），
   * 于是**其余五条路径**都还是 ProblemDetail。P2 深审用 curl 逐条实测后补齐（03 §30 的 P1-1）。
   * 刻意**保留原状态码**而不是统统改成 400：405/415 的语义不该被抹掉，前端要的只是一个能分流的 `code`。
   */
  private static ResponseEntity<Object> asResult(HttpStatusCode status, String message) {
    return ResponseEntity.status(status).body(Result.fail(ErrorCode.BAD_REQUEST.code(), message));
  }

  /**
   * 缺少必填参数（如 `DELETE /dimensions/{id}` 漏传 `version`）。
   *
   * <p>⚠️ 这条是 W3 引入 `@RequestParam int version` 后**新增**的高频路径——漏传就走到这里。
   */
  @Override
  protected ResponseEntity<Object> handleMissingServletRequestParameter(
      MissingServletRequestParameterException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    log.warn("缺少必需参数：{}", ex.getParameterName());
    return asResult(status, "缺少必需的参数：" + ex.getParameterName());
  }

  /**
   * 参数类型不符（如 `?page=abc`、`/indicators/abc`）。
   *
   * <p>⚠️ 这一条用 `@ExceptionHandler` 而**不是** `@Override`：`javap` 实测 Spring 6.2 的
   * {@code ResponseEntityExceptionHandler} **已经没有** {@code handleMethodArgumentTypeMismatch}
   * 钩子（其余四个仍在），它走的是 `ErrorResponse` 的通用路径、产出 `ProblemDetail`。
   * 一开始我照其它三条的样子写 `@Override`，编译直接报"方法不会覆盖超类型的成员"——这就是
   * "不猜框架的扩展点、去看它到底提供了什么"的价值（题面与实现不一致时，**去看实现**）。
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Result<Void>> handleMethodArgumentTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    log.warn("参数类型不符：{}={}", ex.getName(), ex.getValue());
    return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
        .body(Result.fail(ErrorCode.BAD_REQUEST.code(), "参数 " + ex.getName() + " 的取值不合法"));
  }

  /** 方法不允许（如对只读端点发 PATCH）。 */
  @Override
  protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
      HttpRequestMethodNotSupportedException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    log.warn("方法不允许：{}", ex.getMessage());
    return asResult(status, "该接口不支持此请求方法");
  }

  /** 媒体类型不支持（如 `Content-Type: text/plain` 提交 JSON 接口）。 */
  @Override
  protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    log.warn("媒体类型不支持：{}", ex.getContentType());
    return asResult(status, "请求的 Content-Type 不受支持，请使用 application/json");
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

  /**
   * 方法级校验失败（类上 {@code @Validated} + 参数上的约束注解）：与 body 校验同样归 2xxx。
   * 缺少本处理器时它会落到下面的 {@code Exception} 兜底，返回 5xxx 并被当成系统异常。
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
    String message = ex.getConstraintViolations().stream()
        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
        .collect(Collectors.joining("；"));
    log.warn("参数校验失败：{}", message);
    return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
        .body(Result.fail(ErrorCode.BAD_REQUEST.code(), message.isEmpty() ? ErrorCode.BAD_REQUEST.message() : message));
  }
}
