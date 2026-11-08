package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E2：门户邀请 Token 接受与一次性约束（docs/erp/56 §2）。 */
@Transactional
class SupplierPortalInvitationApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("邀请 Token 创建、接受、登录与一次性/无效拒绝")
  void acceptInvitation() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long supplierId = createSupplier(admin, "INV-SUP");
    JsonNode invitation =
        performOk(
            authed(
                post("/api/suppliers/" + supplierId + "/portal-invitation")
                    .param("ttlHours", "24"),
                admin));
    String token = invitation.path("token").asText();
    assertThat(token).isNotBlank();

    JsonNode accepted =
        performOk(
            json(
                post("/api/supplier-portal/invitations/" + token + "/accept"),
                Map.of("loginName", "inv-portal", "password", DEFAULT_PASSWORD)));
    assertThat(accepted.path("supplierId").asLong()).isEqualTo(supplierId);
    assertThat(accepted.path("portalUserId").asLong()).isPositive();

    JsonNode login =
        performOk(
            json(
                post("/api/supplier-portal/auth/login"),
                Map.of("loginName", "inv-portal", "password", DEFAULT_PASSWORD)));
    assertThat(login.path("accessToken").asText()).isNotBlank();

    // 同一 Token 不能再次接受
    mockMvc
        .perform(
            json(
                post("/api/supplier-portal/invitations/" + token + "/accept"),
                Map.of("loginName", "inv-portal-2", "password", DEFAULT_PASSWORD)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4007));

    // 无效 Token 同样不可枚举
    mockMvc
        .perform(
            json(
                post("/api/supplier-portal/invitations/not-a-token/accept"),
                Map.of("loginName", "inv-portal-2", "password", DEFAULT_PASSWORD)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4007));
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/suppliers"),
                Map.of("code", code, "name", "INV 供应商 " + code, "category", "电子")),
            token));
    Long id =
        jdbcTemplate.queryForObject("SELECT id FROM supplier WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException("供应商不存在：" + code);
    }
    return id;
  }

  private JsonNode performOk(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    MvcResult result =
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
  }
}
