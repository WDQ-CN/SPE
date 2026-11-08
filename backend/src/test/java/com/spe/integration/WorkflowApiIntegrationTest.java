package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.api.Role;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E6 通用工作流：定义、实例、角色审批与 SoD（docs/erp/39）。 */
@Transactional
class WorkflowApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("两节点串行审批、角色守卫、驳回与待办查询")
  void workflowDefinitionAndApproval() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String manager1 = loginAs(admin, "wf-manager-1", Role.MANAGER);
    String manager2 = loginAs(admin, "wf-manager-2", Role.MANAGER);
    long companyId = createCompany(admin, "WF-CO");

    JsonNode definition =
        performOk(
            authed(
                json(
                    post("/api/workflows/definitions"),
                    Map.of(
                        "code", "PO-APPROVAL",
                        "name", "采购订单审批",
                        "docType", "PURCHASE_ORDER",
                        "versionNo", "V1",
                        "conditionMinAmount", 0,
                        "nodes", List.of(
                            Map.of("name", "采购经理", "approverRole", "MANAGER"),
                            Map.of("name", "复核经理", "approverRole", "MANAGER")))),
                admin));
    long definitionId = definition.path("id").asLong();
    JsonNode active =
        performOk(
            authed(post("/api/workflows/definitions/" + definitionId + "/activate"), admin));
    assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(active.path("nodes").size()).isEqualTo(2);

    JsonNode instance =
        performOk(
            authed(
                json(
                    post("/api/workflows/instances"),
                    Map.of(
                        "companyId", companyId,
                        "docType", "PURCHASE_ORDER",
                        "docId", 9001,
                        "amount", 1000)),
                admin));
    long instanceId = instance.path("id").asLong();
    long task1 = instance.path("tasks").get(0).path("id").asLong();

    mockMvc
        .perform(
            authed(
                json(post("/api/workflows/tasks/" + task1 + "/approve"), Map.of("opinion", "同意")),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2261));

    JsonNode afterFirst =
        performOk(
            authed(
                json(post("/api/workflows/tasks/" + task1 + "/approve"), Map.of("opinion", "同意")),
                manager1));
    assertThat(afterFirst.path("currentSeq").asInt()).isEqualTo(2);
    long task2 = afterFirst.path("tasks").get(1).path("id").asLong();
    JsonNode pending =
        performOk(authed(get("/api/workflows/tasks").param("role", "MANAGER"), manager2));
    assertThat(pending).hasSize(1);
    assertThat(pending.get(0).path("id").asLong()).isEqualTo(task2);

    JsonNode approved =
        performOk(
            authed(
                json(post("/api/workflows/tasks/" + task2 + "/approve"), Map.of("opinion", "复核通过")),
                manager2));
    assertThat(approved.path("status").asText()).isEqualTo("APPROVED");

    // 驳回路径：意见必填
    JsonNode second =
        performOk(
            authed(
                json(
                    post("/api/workflows/instances"),
                    Map.of(
                        "companyId", companyId,
                        "docType", "PURCHASE_ORDER",
                        "docId", 9002,
                        "amount", 500)),
                admin));
    long secondTask = second.path("tasks").get(0).path("id").asLong();
    mockMvc
        .perform(
            authed(
                json(post("/api/workflows/tasks/" + secondTask + "/reject"), Map.of()),
                manager1))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
    JsonNode rejected =
        performOk(
            authed(
                json(
                    post("/api/workflows/tasks/" + secondTask + "/reject"),
                    Map.of("opinion", "价格不合理")),
                manager1));
    assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
  }

  private String loginAs(String adminToken, String username, Role role) throws Exception {
    performOk(
        authed(
            json(
                post("/api/users"),
                Map.of(
                    "username", username,
                    "password", DEFAULT_PASSWORD,
                    "name", "WF 用例账号",
                    "role", role.name(),
                    "department", "PURCHASE")),
            adminToken));
    return login(username, DEFAULT_PASSWORD);
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "WF 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    Long id =
        jdbcTemplate.queryForObject("SELECT id FROM company WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException("公司不存在");
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
