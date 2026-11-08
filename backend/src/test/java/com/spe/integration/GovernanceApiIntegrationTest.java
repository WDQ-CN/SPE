package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.api.Role;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E6 治理：SoD 检查/例外审批与 traceId 贯穿（docs/erp/41）。 */
@Transactional
class GovernanceApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("SoD 冲突检测、限时例外审批与 traceId 回写/审计")
  void sodAndTrace() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String approver = loginAs(admin, "gov-approver", Role.ADMIN);
    loginAs(admin, "gov-beneficiary", Role.MANAGER);
    long beneficiaryId = userIdOf("gov-beneficiary");

    JsonNode rule =
        performOk(
            authed(
                json(
                    post("/api/governance/sod/rules"),
                    Map.of(
                        "code", "BUYER-RECEIVER",
                        "roleA", "BUYER",
                        "roleB", "RECEIVER",
                        "description", "采购与收货不能同一人")),
                admin));
    long ruleId = rule.path("id").asLong();

    // 不能为本人申请例外
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/governance/sod/exceptions"),
                    Map.of(
                        "userId", userIdOf(ADMIN_USERNAME),
                        "ruleId", ruleId,
                        "reason", "自批",
                        "validFrom", LocalDate.now(ZoneOffset.UTC).toString(),
                        "validTo", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString())),
                admin))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    JsonNode conflicts =
        performOk(
            authed(
                json(
                    post("/api/governance/sod/check"),
                    Map.of("roles", List.of("BUYER", "RECEIVER"))),
                admin));
    assertThat(conflicts).hasSize(1);
    assertThat(conflicts.get(0).path("excepted").asBoolean()).isFalse();

    JsonNode exception =
        performOk(
            authed(
                json(
                    post("/api/governance/sod/exceptions"),
                    Map.of(
                        "userId", beneficiaryId,
                        "ruleId", ruleId,
                        "reason", "小团队临时顶岗",
                        "validFrom", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString(),
                        "validTo", LocalDate.now(ZoneOffset.UTC).plusDays(30).toString())),
                admin));
    long exceptionId = exception.path("id").asLong();
    JsonNode approved =
        performOk(
            authed(
                post("/api/governance/sod/exceptions/" + exceptionId + "/approve"), approver));
    assertThat(approved.path("status").asText()).isEqualTo("APPROVED");

    JsonNode excepted =
        performOk(
            authed(
                json(
                    post("/api/governance/sod/check"),
                    Map.of(
                        "roles", List.of("BUYER", "RECEIVER"),
                        "userId", beneficiaryId)),
                admin));
    assertThat(excepted.get(0).path("excepted").asBoolean()).isTrue();

    // traceId 回写
    mockMvc
        .perform(
            get("/api/masterdata/companies")
                .header("Authorization", "Bearer " + admin)
                .header("X-Trace-Id", "trace-gov-check"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Trace-Id", "trace-gov-check"));

    // traceId 落审计
    MvcResult result =
        mockMvc
            .perform(
                json(
                    post("/api/governance/sod/rules"),
                    Map.of(
                        "code", "TRACE-RULE",
                        "roleA", "APPROVER",
                        "roleB", "PAYER",
                        "description", "trace 用例"))
                    .header("Authorization", "Bearer " + admin)
                    .header("X-Trace-Id", "trace-gov-audit"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
    assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo("trace-gov-audit");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT trace_id FROM audit_log WHERE action = 'SOD_RULE_CREATE'"
                    + " ORDER BY id DESC LIMIT 1",
                String.class))
        .isEqualTo("trace-gov-audit");
  }

  private String loginAs(String adminToken, String username, Role role) throws Exception {
    performOk(
        authed(
            json(
                post("/api/users"),
                Map.of(
                    "username", username,
                    "password", DEFAULT_PASSWORD,
                    "name", "GOV 用例账号",
                    "role", role.name(),
                    "department", "PURCHASE")),
            adminToken));
    return login(username, DEFAULT_PASSWORD);
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
