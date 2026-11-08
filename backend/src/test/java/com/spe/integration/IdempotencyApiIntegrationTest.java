package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.api.Role;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E6 数据平台：Idempotency-Key 持久化幂等（docs/erp/42）。 */
@Transactional
class IdempotencyApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("同键同内容回放、同键不同内容 2247、无键正常执行")
  void idempotentWrite() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Map<String, Object> body =
        Map.of("code", "IDEM-CO", "name", "幂等公司", "baseCurrency", "CNY", "country", "CN");

    MvcResult first = createCompany(admin, "idem-1", body);
    JsonNode firstData = data(first);
    long firstId = firstData.path("id").asLong();

    MvcResult replay = createCompany(admin, "idem-1", body);
    JsonNode replayData = data(replay);
    assertThat(replayData.path("id").asLong()).isEqualTo(firstId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM company WHERE code = 'IDEM-CO'", Long.class))
        .isEqualTo(1L);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'COMPANY_CREATE'", Long.class))
        .isEqualTo(1L);

    // 幂等作用域必须按调用者隔离：另一个管理员复用同一 key 不能回放/占用前者的记录
    String anotherAdmin = createAdmin(admin, "idem-admin-2");
    MvcResult otherUser =
        createCompany(
            anotherAdmin,
            "idem-1",
            Map.of(
                "code", "IDEM-CO-OTHER",
                "name", "另一个管理员创建",
                "baseCurrency", "CNY",
                "country", "CN"));
    assertThat(data(otherUser).path("id").asLong()).isNotEqualTo(firstId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM company WHERE code = 'IDEM-CO-OTHER'", Long.class))
        .isEqualTo(1L);

    MvcResult conflict =
        mockMvc
            .perform(
                post("/api/masterdata/companies")
                    .header("Authorization", "Bearer " + admin)
                    .header("Idempotency-Key", "idem-1")
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(
                        Map.of("code", "IDEM-CO-2", "name", "不同内容", "baseCurrency", "CNY", "country", "CN"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(2247))
            .andReturn();
    assertThat(conflict.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .contains("2247");

    // 无幂等键照常执行
    MvcResult noKey =
        mockMvc
            .perform(
                post("/api/masterdata/companies")
                    .header("Authorization", "Bearer " + admin)
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(
                        Map.of("code", "IDEM-CO-3", "name", "无键", "baseCurrency", "CNY", "country", "CN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
    assertThat(data(noKey).path("id").asLong()).isPositive();
  }

  private String createAdmin(String adminToken, String username) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", username,
                        "password", DEFAULT_PASSWORD,
                        "name", "幂等用例管理员",
                        "role", Role.ADMIN.name(),
                        "department", "PURCHASE")),
                adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    return login(username, DEFAULT_PASSWORD);
  }

  private MvcResult createCompany(
      String token, String key, Map<String, Object> body) throws Exception {
    return mockMvc
        .perform(
            post("/api/masterdata/companies")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andReturn();
  }

  private JsonNode data(MvcResult result) throws Exception {
    return objectMapper
        .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .path("data");
  }
}
