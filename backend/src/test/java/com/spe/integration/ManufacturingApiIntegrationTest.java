package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E4 制造：BOM、工单、领料、完工入库（docs/erp/36）。 */
@Transactional
class ManufacturingApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("BOM 生效、工单下达、领料出库、完工入库与超限守卫")
  void bomWorkOrderLifecycle() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "MF-CO");
    long plantId = createPlant(admin, companyId, "MF-PL");
    long warehouseId = createWarehouse(admin, plantId, "MF-WH");
    long locationId = createLocation(admin, warehouseId, "MF-BIN");
    long component1 = createItem(admin, "MF-C1", "RAW");
    long component2 = createItem(admin, "MF-C2", "RAW");
    long finished = createItem(admin, "MF-FG", "FINISHED");
    insertStock(companyId, plantId, warehouseId, locationId, component1, "10", "10");
    insertStock(companyId, plantId, warehouseId, locationId, component2, "5", "20");

    JsonNode bom =
        performOk(
            authed(
                json(
                    post("/api/manufacturing/boms"),
                    Map.of(
                        "companyId", companyId,
                        "itemId", finished,
                        "version", "V1",
                        "lines", List.of(
                            Map.of("componentItemId", component1, "qty", 2, "uom", "EA"),
                            Map.of("componentItemId", component2, "qty", 1, "uom", "EA")))),
                admin));
    long bomId = bom.path("id").asLong();
    assertThat(bom.path("status").asText()).isEqualTo("DRAFT");
    JsonNode activeBom =
        performOk(authed(post("/api/manufacturing/boms/" + bomId + "/activate"), admin));
    assertThat(activeBom.path("status").asText()).isEqualTo("ACTIVE");

    JsonNode wo =
        performOk(
            authed(
                json(
                    post("/api/manufacturing/work-orders"),
                    Map.of(
                        "companyId", companyId,
                        "plantId", plantId,
                        "itemId", finished,
                        "bomId", bomId,
                        "qty", 5)),
                admin));
    long woId = wo.path("id").asLong();
    JsonNode released =
        performOk(authed(post("/api/manufacturing/work-orders/" + woId + "/release"), admin));
    assertThat(released.path("status").asText()).isEqualTo("RELEASED");
    assertThat(released.path("components").size()).isEqualTo(2);
    long woComponent1 = released.path("components").get(0).path("id").asLong();
    long woComponent2 = released.path("components").get(1).path("id").asLong();
    assertThat(released.path("components").get(0).path("qtyRequired").decimalValue())
        .isEqualByComparingTo("10.000000");

    // 超领 11 → 2605
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/manufacturing/work-orders/" + woId + "/issues"),
                    Map.of(
                        "warehouseId", warehouseId,
                        "lines", List.of(Map.of(
                            "componentId", woComponent1,
                            "qty", 11,
                            "locationId", locationId)))),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2605));

    JsonNode issued =
        performOk(
            authed(
                json(
                    post("/api/manufacturing/work-orders/" + woId + "/issues"),
                    Map.of(
                        "warehouseId", warehouseId,
                        "lines", List.of(
                            Map.of(
                                "componentId", woComponent1,
                                "qty", 10,
                                "locationId", locationId),
                            Map.of(
                                "componentId", woComponent2,
                                "qty", 5,
                                "locationId", locationId)))),
                admin));
    assertThat(issued.path("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(issued.path("materialCost").decimalValue()).isEqualByComparingTo("200.0000");
    assertThat(stockQty(companyId, component1)).isEqualByComparingTo("0.000000");
    assertThat(stockQty(companyId, component2)).isEqualByComparingTo("0.000000");

    // 超入 6 → 2606
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/manufacturing/work-orders/" + woId + "/receipts"),
                    Map.of(
                        "warehouseId", warehouseId,
                        "locationId", locationId,
                        "qty", 6)),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2606));

    JsonNode completed =
        performOk(
            authed(
                json(
                    post("/api/manufacturing/work-orders/" + woId + "/receipts"),
                    Map.of(
                        "warehouseId", warehouseId,
                        "locationId", locationId,
                        "qty", 5)),
                admin));
    assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(completed.path("receivedQty").decimalValue()).isEqualByComparingTo("5.000000");
    assertThat(completed.path("receipts").get(0).path("unitCost").decimalValue())
        .isEqualByComparingTo("40.000000");
    assertThat(stockQty(companyId, finished)).isEqualByComparingTo("5.000000");
    assertThat(stockValue(companyId, finished)).isEqualByComparingTo("200.0000");

    JsonNode closed =
        performOk(authed(post("/api/manufacturing/work-orders/" + woId + "/close"), admin));
    assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
  }

  private void insertStock(
      long companyId,
      long plantId,
      long warehouseId,
      long locationId,
      long itemId,
      String qty,
      String unitCost) {
    BigDecimal quantity = new BigDecimal(qty);
    BigDecimal cost = new BigDecimal(unitCost);
    jdbcTemplate.update(
        "INSERT INTO inventory_stock (company_id, plant_id, warehouse_id, item_id, lot_id,"
            + " location_id, qty_on_hand, qty_reserved, qty_frozen, total_value, avg_unit_cost,"
            + " version) VALUES (?, ?, ?, ?, 0, ?, ?, 0, 0, ?, ?, 0)",
        companyId,
        plantId,
        warehouseId,
        itemId,
        locationId,
        quantity,
        quantity.multiply(cost),
        cost);
  }

  private BigDecimal stockQty(long companyId, long itemId) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(qty_on_hand), 0) FROM inventory_stock WHERE company_id = ?"
            + " AND item_id = ?",
        BigDecimal.class,
        companyId,
        itemId);
  }

  private BigDecimal stockValue(long companyId, long itemId) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(total_value), 0) FROM inventory_stock WHERE company_id = ?"
            + " AND item_id = ?",
        BigDecimal.class,
        companyId,
        itemId);
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "MF 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/plants"),
                Map.of("companyId", companyId, "code", code, "name", "MF 工厂 " + code)),
            token));
    return idOf("plant", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of("plantId", plantId, "code", code, "name", "MF 仓库 " + code,
                    "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", code);
  }

  private long createLocation(String token, long warehouseId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses/" + warehouseId + "/locations"),
                Map.of("code", code, "name", "库位 " + code)),
            token));
    return idOf("warehouse_location", code);
  }

  private long createItem(String token, String code, String itemType) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/items"),
                Map.of(
                    "code", code,
                    "name", "MF 物料 " + code,
                    "itemType", itemType,
                    "baseUom", "EA",
                    "lotControlled", false,
                    "serialControlled", false,
                    "valuationMethod", "AVCO")),
            token));
    return idOf("item", code);
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
