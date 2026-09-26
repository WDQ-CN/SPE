package com.spe.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局异常处理器的 2xxx 校验失败分支（09 WP4）。
 *
 * <p>该分支需要真实的参数绑定与 JSR-303 校验才会触发（直接构造 {@code MethodArgumentNotValidException}
 * 要拼 {@code MethodParameter} + {@code BindingResult}，与真实链路脱节），故用 standalone MockMvc——
 * 不加载 Spring 上下文，仍属单元测试范围（09 D7）。
 */
class GlobalExceptionHandlerValidationTest {

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new ProbeController())
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  @Test
  @DisplayName("同一个端点：合法载荷正常通过，非法载荷 400 + Result(2001)（对照才看得出 advice 没误伤）")
  void validationFailureBecomesBadRequestResult() throws Exception {
    // 先走一遍合法载荷：只测失败分支的话，「处理器把正常请求也拦下来了」这种缺陷不会被发现
    mockMvc
        .perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE));

    mockMvc
        .perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.code()))
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(jsonPath("$.message").value(containsString("name")));
  }

  @Test
  @DisplayName("方法级校验失败：400 + Result(2001)，不落 5xxx 兜底")
  void methodValidationFailureBecomesBadRequestResult() throws Exception {
    mockMvc
        .perform(post("/probe/constraint"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.code()))
        .andExpect(jsonPath("$.message").value(containsString("不能为空")));
  }

  /** 校验探针用的载荷。 */
  record ProbePayload(@NotBlank(message = "不能为空") String name) {
  }

  /** 仅用于触发参数校验的探针 Controller，不参与生产代码。 */
  @RestController
  static class ProbeController {

    @PostMapping("/probe")
    Result<Void> probe(@Valid @RequestBody ProbePayload payload) {
      return Result.ok();
    }

    /**
     * 类上 {@code @Validated} + 参数约束触发的是 {@code ConstraintViolationException}。
     * standalone MockMvc 不装配 {@code MethodValidationPostProcessor}（方法级校验依赖 AOP 代理），
     * 故这里抛出同类异常，验证它在 HTTP 层被映射为 400 + 2001 而不是 5xxx。
     */
    @PostMapping("/probe/constraint")
    Result<Void> probeConstraint() {
      Set<ConstraintViolation<ProbePayload>> violations =
          Validation.buildDefaultValidatorFactory().getValidator().validate(new ProbePayload(""));
      throw new ConstraintViolationException(violations);
    }
  }
}
