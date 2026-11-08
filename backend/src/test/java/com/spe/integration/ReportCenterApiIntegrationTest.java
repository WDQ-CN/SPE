package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** E6 报表中心：固定报表、运行快照与 CSV 导出（docs/erp/39）。 */
@Transactional
class ReportCenterApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("库存报表运行、快照查询与 CSV 导出；空应收报表")
  void reportRuns() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "RPT-CO");
    long itemId = createItem(admin, "RPT-IT");
    long warehouseId = createWarehouse(admin, companyId, "RPT-WH");
    jdbcTemplate.update(
        "INSERT INTO inventory_stock (company_id, plant_id, warehouse_id, item_id, lot_id,"
            + " location_id, qty_on_hand, qty_reserved, qty_frozen, total_value, avg_unit_cost,"
            + " version) VALUES (?, 0, ?, ?, 0, 0, 10, 0, 0, 600, 60, 0)",
        companyId,
        warehouseId,
        itemId);

    JsonNode definitions = performOk(authed(get("/api/reports"), admin));
    assertThat(definitions.size()).isGreaterThanOrEqualTo(6);

    JsonNode run =
        performOk(
            authed(
                json(post("/api/reports/INVENTORY_STOCK/runs"), Map.of("companyId", companyId)),
                admin));
    assertThat(run.path("status").asText()).isEqualTo("SUCCESS");
    assertThat(run.path("rowCount").asInt()).isEqualTo(1);
    long runId = run.path("id").asLong();

    JsonNode detail = performOk(authed(get("/api/reports/runs/" + runId), admin));
    assertThat(detail.path("code").asText()).isEqualTo("INVENTORY_STOCK");

    MvcResult csv =
        mockMvc
            .perform(authed(get("/api/reports/runs/" + runId + "/download"), admin))
            .andExpect(status().isOk())
            .andReturn();
    String text = csv.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(text).contains("qtyOnHand").contains("10.0");

    JsonNode empty =
        performOk(
            authed(
                json(post("/api/reports/AR_AGING/runs"), Map.of("companyId", companyId)),
                admin));
    assertThat(empty.path("rowCount").asInt()).isZero();

    JsonNode history =
        performOk(
            authed(
                get("/api/reports/runs?companyId=" + companyId + "&page=1&size=10"), admin));
    assertThat(history.path("total").asLong()).isEqualTo(2);
    assertThat(history.path("list").get(0).path("code").asText()).isEqualTo("AR_AGING");
    assertThat(history.path("list").get(1).path("code").asText()).isEqualTo("INVENTORY_STOCK");
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "RPT 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", code);
  }

  private long createItem(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/items"),
                Map.of(
                    "code", code,
                    "name", "RPT 物料 " + code,
                    "itemType", "RAW",
                    "baseUom", "EA",
                    "lotControlled", false,
                    "serialControlled", false,
                    "valuationMethod", "AVCO")),
            token));
    return idOf("item", code);
  }

  private long createWarehouse(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/plants"),
                Map.of("companyId", companyId, "code", code + "-PL", "name", "RPT 工厂")),
            token));
    long plantId = idOf("plant", code + "-PL");
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of("plantId", plantId, "code", code, "name", "RPT 仓库",
                    "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", code);
  }

  private long idOf(String table, String code) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException(table + " 不存在：" + code);
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
