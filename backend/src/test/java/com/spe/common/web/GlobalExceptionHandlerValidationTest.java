package com.spe.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
  @DisplayName("请求体校验失败：400 + Result(2001)，message 带字段名供表单回显")
  void validationFailureBecomesBadRequestResult() throws Exception {
    mockMvc
        .perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.code()))
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(jsonPath("$.message").value(containsString("name")));
  }

  @Test
  @DisplayName("校验通过：正常走到 Controller 并返回 Result(code=0)")
  void validRequestReachesController() throws Exception {
    mockMvc
        .perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ok\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(Result.SUCCESS_CODE));
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
  }
}
