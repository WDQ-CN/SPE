package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

/**
 * 框架级异常路径必须也返回带 `code` 的 {@link com.spe.common.web.Result}（03 §30 的 P1-1 / P1-2）。
 *
 * <p>为什么值得单独钉住：`ResponseEntityExceptionHandler` 默认产出 `ProblemDetail`
 * （`application/problem+json`），**里面没有 `code`**；而前端 `app/http/index.ts` 按
 * `typeof result.code === 'number'` 分流——拿不到 code 就落到传输层兜底，用户看到一句
 * 「请求未被接受（400）」，2xxx 的表单回显与 3xxx/4xxx 的全局提示**同时失效**。
 *
 * <p>W3 只覆写了 `handleHttpMessageNotReadable`（非法枚举），P2 深审用 curl 逐条实测后补齐了其余路径。
 * 这组用例的价值在于：**它们会在有人改动异常处理时立刻变红**，而不是等到某个用户在界面上看到一句
 * 无信息的话。特别注意 405/415 两条——本条修复刻意**保留原 HTTP 状态**（不统统改成 400），
 * 所以断言的是 `isMethodNotAllowed()`/`isUnsupportedMediaType()` 而**不是** `isBadRequest()`。
 */
@Transactional
class GlobalExceptionHandlerApiTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("缺少必填 query 参数 → 400 + code 2001（W3 新增的 DELETE 端点最容易走到这条）")
  void missingRequestParameterBecomesResult() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockMvc
        .perform(authed(delete("/api/dimensions/99999999"), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("version")));
  }

  @Test
  @DisplayName("参数类型不符 → 400 + code 2001（`?page=abc` 与路径变量都走这条）")
  void argumentTypeMismatchBecomesResult() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockMvc
        .perform(authed(get("/api/templates?page=abc&size=10"), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));

    mockMvc
        .perform(authed(put("/api/indicators/abc"), token).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
  }

  @Test
  @DisplayName("方法不允许 → 仍是 405，但响应体是 Result（保留原状态码，只改响应体）")
  void methodNotAllowedKeepsStatusButReturnsResult() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockMvc
        .perform(authed(patch("/api/templates"), token).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value(2001));
  }

  @Test
  @DisplayName("媒体类型不支持 → 仍是 415，但响应体是 Result")
  void unsupportedMediaTypeKeepsStatusButReturnsResult() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    mockMvc
        .perform(
            authed(post("/api/templates"), token)
                .contentType(MediaType.TEXT_PLAIN)
                .content("name=x"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value(2001));
  }

  @Test
  @DisplayName("未知/拼错的 JSON 字段 → 400 + 2001（不能静默丢弃：PUT 是全量覆盖，漏传会静默清空）")
  void unknownJsonPropertyIsRejected() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);

    Map<String, Object> withTypo = new HashMap<>();
    withTypo.put("name", "不该被创建");
    // 典型拼写错误：把 targetValue 写成 target，或把 weight 放到不属于它的 DTO 上。
    // Boot 默认 fail-on-unknown-properties=false 时，这个字段会被**静默丢弃**、请求照样 200。
    withTypo.put("weight", "abc");

    mockMvc
        .perform(authed(json(post("/api/templates"), withTypo), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));

    // 顺带证明它确实没被创建
    Integer created =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM template WHERE name = ?", Integer.class, "不该被创建");
    assertThat(created).as("未知字段的请求不应产生数据").isZero();
  }
}
