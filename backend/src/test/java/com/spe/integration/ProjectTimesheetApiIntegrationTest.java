package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** E5 项目：项目/WBS/预算/工时与预算控制（docs/erp/38）。 */
@Transactional
class ProjectTimesheetApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("项目审批/激活、预算、工时审批与超预算守卫")
  void projectTimesheetLifecycle() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String approver = loginAs(admin, "prj-approver", Role.ADMIN);
    long companyId = createCompany(admin, "PRJ-CO");
    JsonNode project =
        performOk(
            authed(
                json(
                    post("/api/projects"),
                    Map.of(
                        "companyId", companyId,
                        "name", "产线数字化项目",
                        "currency", "CNY",
                        "defaultLaborRate", 50,
                        "startDate", LocalDate.now(ZoneOffset.UTC).toString())),
                admin));
    long projectId = project.path("id").asLong();
    assertThat(project.path("status").asText()).isEqualTo("DRAFT");

    JsonNode withWbs =
        performOk(
            authed(
                json(
                    post("/api/projects/" + projectId + "/wbs"),
                    Map.of("code", "1.1", "name", "需求与设计", "budget", 500)),
                admin));
    long wbsId = withWbs.path("wbs").get(0).path("id").asLong();

    JsonNode budget =
        performOk(
            authed(
                json(
                    post("/api/projects/" + projectId + "/budgets"),
                    Map.of(
                        "versionNo", "V1",
                        "laborAmount", 800,
                        "purchaseAmount", 200,
                        "expenseAmount", 0,
                        "otherAmount", 0)),
                admin));
    long budgetId = budget.path("budgets").get(0).path("id").asLong();
    performOk(authed(post("/api/projects/budgets/" + budgetId + "/approve"), admin));

    mockMvc
        .perform(authed(post("/api/projects/" + projectId + "/approve"), admin))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));
    JsonNode approved =
        performOk(authed(post("/api/projects/" + projectId + "/approve"), approver));
    assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
    assertThat(approved.path("budgetAmount").decimalValue()).isEqualByComparingTo("1000.0000");
    performOk(authed(post("/api/projects/" + projectId + "/activate"), approver));

    JsonNode timesheet =
        performOk(
            authed(
                json(
                    post("/api/timesheets"),
                    Map.of(
                        "companyId", companyId,
                        "lines", List.of(Map.of(
                            "projectId", projectId,
                            "wbsId", wbsId,
                            "workDate", LocalDate.now(ZoneOffset.UTC).toString(),
                            "hours", 10)))),
                admin));
    long timesheetId = timesheet.path("id").asLong();
    assertThat(timesheet.path("totalAmount").decimalValue()).isEqualByComparingTo("500.0000");
    performOk(authed(post("/api/timesheets/" + timesheetId + "/submit"), admin));
    JsonNode approvedTimesheet =
        performOk(authed(post("/api/timesheets/" + timesheetId + "/approve"), approver));
    assertThat(approvedTimesheet.path("status").asText()).isEqualTo("APPROVED");
    JsonNode projectAfter =
        performOk(authed(get("/api/projects/" + projectId), admin));
    assertThat(projectAfter.path("actualCost").decimalValue()).isEqualByComparingTo("500.0000");

    // 超预算：550 + 500 > 1000
    JsonNode over =
        performOk(
            authed(
                json(
                    post("/api/timesheets"),
                    Map.of(
                        "companyId", companyId,
                        "lines", List.of(Map.of(
                            "projectId", projectId,
                            "workDate", LocalDate.now(ZoneOffset.UTC).toString(),
                            "hours", 11)))),
                admin));
    long overId = over.path("id").asLong();
    performOk(authed(post("/api/timesheets/" + overId + "/submit"), admin));
    mockMvc
        .perform(authed(post("/api/timesheets/" + overId + "/approve"), approver))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2904));

    JsonNode closed =
        performOk(authed(post("/api/projects/" + projectId + "/close"), approver));
    assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
  }

  private String loginAs(String adminToken, String username, Role role) throws Exception {
    performOk(
        authed(
            json(
                post("/api/users"),
                Map.of(
                    "username", username,
                    "password", DEFAULT_PASSWORD,
                    "name", "PRJ 用例账号",
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
                Map.of("code", code, "name", "PRJ 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
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
