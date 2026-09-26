package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录失败限流的端到端行为（03 §16 的 SEC-5）：连续失败达到阈值后，登录返回 **429 + 1003**，
 * 而不是继续无限次比对口令；同时只为「进入锁定」那一刻留一条 `AUTH_LOGIN_BLOCKED` 审计。
 *
 * <p>本用例刻意用**独立来源 IP**（`withRemoteAddr`）：限流器是进程内单例，IP 维度按来源累计，
 * 若与其它用例共用 MockMvc 的默认地址，次数会互相累加、让测试变得依赖执行顺序。
 */
@Transactional
class LoginRateLimitIntegrationTest extends AbstractIntegrationTest {

  private static final String PROBE_ACCOUNT = "rate_limit_probe";
  private static final String PROBE_IP = "198.18.0.77";

  private MockHttpServletRequestBuilder loginAttempt() throws Exception {
    return json(
            post("/api/auth/login"),
            Map.of("username", PROBE_ACCOUNT, "password", "definitely-wrong"))
        .with(
            request -> {
              request.setRemoteAddr(PROBE_IP);
              return request;
            });
  }

  @Test
  @DisplayName("连续失败到阈值 → 429 + 1003，并落一条 AUTH_LOGIN_BLOCKED")
  void repeatedFailuresGetRateLimited() throws Exception {
    for (int i = 0; i < 5; i++) {
      mockMvc
          .perform(loginAttempt())
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value(1002));
    }

    mockMvc
        .perform(loginAttempt())
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(1003));

    assertThat(auditRows("AUTH_LOGIN_BLOCKED"))
        .as("进入锁定的那一刻应留下一条审计")
        .isNotEmpty();
  }
}
