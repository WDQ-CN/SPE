package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.api.Role;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E2 W2/W3：预留/冻结、调拨、盘点、容量、估值与对账（docs/erp/54）。 */
@Transactional
class InventoryOperationsApiIntegrationTest extends AbstractIntegrationTest {

  private record Setup(
      long companyId,
      long plantId,
      long warehouseId,
      long fromLocationId,
      long toLocationId,
      long itemId,
      long poId,
      String receiver) {}

  @Test
  @DisplayName("预留/冻结、容量、调拨与盘点闭环")
  void allocationTransferAndCount() throws Exception {
    Setup setup = setup("OPS", "AVCO", BigDecimal.ZERO);
    receive(setup, 10);

    // 容量上限 12，当前 10
    performOk(
        authed(
            json(
                put("/api/inventory/locations/" + setup.fromLocationId() + "/capacity"),
                Map.of("itemId", 0, "maxQty", 12, "status", "ACTIVE")),
            setup.receiver()));
    JsonNode capacities =
        performOk(
            authed(
                get("/api/inventory/locations/" + setup.fromLocationId() + "/capacity"),
                setup.receiver()));
    assertThat(capacities.get(0).path("currentQty").decimalValue()).isEqualByComparingTo("10");

    // 预留 4：可用 6；再预留 7 → 2310
    JsonNode reservation =
        performOk(
            authed(
                json(
                    post("/api/inventory/reservations"),
                    Map.of(
                        "companyId", setup.companyId(),
                        "warehouseId", setup.warehouseId(),
                        "locationId", setup.fromLocationId(),
                        "itemId", setup.itemId(),
                        "qty", 4,
                        "sourceType", "SALES_ORDER",
                        "sourceId", 9001)),
                setup.receiver()));
    assertThat(reservation.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(stockField(setup, "qty_reserved")).isEqualByComparingTo("4.000000");
    assertThat(stockField(setup, "qty_on_hand")).isEqualByComparingTo("10.000000");
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/inventory/reservations"),
                    Map.of(
                        "companyId", setup.companyId(),
                        "warehouseId", setup.warehouseId(),
                        "locationId", setup.fromLocationId(),
                        "itemId", setup.itemId(),
                        "qty", 7,
                        "sourceType", "SALES_ORDER",
                        "sourceId", 9002)),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2310));
    performOk(
        authed(
            post("/api/inventory/reservations/" + reservation.path("id").asLong() + "/release"),
            setup.receiver()));
    assertThat(stockField(setup, "qty_reserved")).isEqualByComparingTo("0.000000");

    // 冻结 3：再预留 8 → 2310（可用 7）
    JsonNode freeze =
        performOk(
            authed(
                json(
                    post("/api/inventory/freezes"),
                    Map.of(
                        "companyId", setup.companyId(),
                        "warehouseId", setup.warehouseId(),
                        "locationId", setup.fromLocationId(),
                        "itemId", setup.itemId(),
                        "qty", 3,
                        "reason", "质检隔离",
                        "sourceType", "QUALITY_HOLD",
                        "sourceId", 7001)),
                setup.receiver()));
    assertThat(freeze.path("status").asText()).isEqualTo("ACTIVE");
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/inventory/reservations"),
                    Map.of(
                        "companyId", setup.companyId(),
                        "warehouseId", setup.warehouseId(),
                        "locationId", setup.fromLocationId(),
                        "itemId", setup.itemId(),
                        "qty", 8,
                        "sourceType", "SALES_ORDER",
                        "sourceId", 9003)),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2310));
    performOk(
        authed(post("/api/inventory/freezes/" + freeze.path("id").asLong() + "/release"), setup.receiver()));
    assertThat(stockField(setup, "qty_frozen")).isEqualByComparingTo("0.000000");

    // 目标库位容量 4：调拨 5 发运成功、接收 2312
    performOk(
        authed(
            json(
                put("/api/inventory/locations/" + setup.toLocationId() + "/capacity"),
                Map.of("itemId", 0, "maxQty", 4, "status", "ACTIVE")),
            setup.receiver()));
    JsonNode transfer =
        performOk(
            authed(
                json(
                    post("/api/inventory/transfers"),
                    Map.of(
                        "companyId", setup.companyId(),
                        "fromWarehouseId", setup.warehouseId(),
                        "fromLocationId", setup.fromLocationId(),
                        "toWarehouseId", setup.warehouseId(),
                        "toLocationId", setup.toLocationId(),
                        "reason", "库位调整",
                        "lines", List.of(Map.of(
                            "itemId", setup.itemId(),
                            "qty", 5,
                            "uom", "EA",
                            "unitCost", 100)))),
                setup.receiver()));
    performOk(
        authed(
            post("/api/inventory/transfers/" + transfer.path("id").asLong() + "/ship"),
            setup.receiver()));
    assertThat(stockAtLocation(setup, setup.fromLocationId())).isEqualByComparingTo("5.000000");
    mockMvc
        .perform(
            authed(
                post("/api/inventory/transfers/" + transfer.path("id").asLong() + "/receive"),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2312));
    performOk(
        authed(
            json(
                put("/api/inventory/locations/" + setup.toLocationId() + "/capacity"),
                Map.of("itemId", 0, "maxQty", 100, "status", "ACTIVE")),
            setup.receiver()));
    performOk(
        authed(
            post("/api/inventory/transfers/" + transfer.path("id").asLong() + "/receive"),
            setup.receiver()));
    assertThat(stockAtLocation(setup, setup.toLocationId())).isEqualByComparingTo("5.000000");
    assertThat(stockAtLocation(setup, setup.fromLocationId())).isEqualByComparingTo("5.000000");

    // 盘点源库位：快照 5，实盘 4，过账后生成 ADJUST
    JsonNode count =
        performOk(
            authed(
                json(
                    post("/api/inventory/counts"),
                    Map.of(
                        "companyId", setup.companyId(),
                        "warehouseId", setup.warehouseId(),
                        "itemId", setup.itemId(),
                        "locationId", setup.fromLocationId(),
                        "reason", "月度盘点")),
                setup.receiver()));
    long lineId = count.path("lines").get(0).path("id").asLong();
    performOk(
        authed(
            json(
                put("/api/inventory/counts/" + count.path("id").asLong() + "/lines"),
                Map.of("lines", List.of(Map.of("lineId", lineId, "countedQty", 4, "reason", "破损")))),
            setup.receiver()));
    performOk(
        authed(post("/api/inventory/counts/" + count.path("id").asLong() + "/submit"), setup.receiver()));
    performOk(
        authed(post("/api/inventory/counts/" + count.path("id").asLong() + "/approve"), setup.receiver()));
    JsonNode posted =
        performOk(
            authed(post("/api/inventory/counts/" + count.path("id").asLong() + "/post"), setup.receiver()));
    assertThat(posted.path("status").asText()).isEqualTo("POSTED");
    assertThat(stockAtLocation(setup, setup.fromLocationId())).isEqualByComparingTo("4.000000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_movement WHERE source_type = 'INVENTORY_COUNT'"
                    + " AND movement_type = 'ADJUST' AND company_id = ?",
                Long.class,
                setup.companyId()))
        .isEqualTo(1L);
    mockMvc
        .perform(
            authed(post("/api/inventory/counts/" + count.path("id").asLong() + "/post"), setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2313));
  }

  @Test
  @DisplayName("标准成本估值与库存-GL 对账")
  void standardCostValuationAndReconciliation() throws Exception {
    Setup setup = setup("STD", "STANDARD", new BigDecimal("80.00"));
    receive(setup, 2);

    JsonNode report =
        performOk(
            authed(
                get("/api/inventory/reports/valuation")
                    .param("companyId", String.valueOf(setup.companyId())),
                setup.receiver()));
    assertThat(report.path("totalValue").decimalValue()).isEqualByComparingTo("160.0000");
    assertThat(report.path("rows").get(0).path("method").asText()).isEqualTo("STANDARD");

    JsonNode reconciliation =
        performOk(
            authed(
                get("/api/finance/reports/inventory-reconciliation")
                    .param("companyId", String.valueOf(setup.companyId())),
                setup.receiver()));
    assertThat(reconciliation.path("subledgerValue").decimalValue()).isEqualByComparingTo("160.0000");
    assertThat(reconciliation.path("glValue").decimalValue()).isEqualByComparingTo("200.0000");
    assertThat(reconciliation.path("difference").decimalValue()).isEqualByComparingTo("-40.0000");
    assertThat(reconciliation.path("balanced").asBoolean()).isFalse();
  }

  private Setup setup(String prefix, String valuationMethod, BigDecimal standardCost) throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, prefix + "-CO");
    long plantId = createPlant(admin, companyId, prefix + "-PL");
    long warehouseId = createWarehouse(admin, plantId, prefix + "-WH");
    long fromLocationId = createLocation(admin, warehouseId, prefix + "-BIN-1");
    long toLocationId = createLocation(admin, warehouseId, prefix + "-BIN-2");
    long supplierId = createSupplier(admin, prefix + "-SUP");
    long itemId = createItem(admin, prefix + "-IT", valuationMethod, standardCost);
    configureFinance(companyId);
    String buyer = loginAs(admin, prefix.toLowerCase() + "-buyer", Role.MANAGER);
    String approver = loginAs(admin, prefix.toLowerCase() + "-approver", Role.ADMIN);
    String receiver = loginAs(admin, prefix.toLowerCase() + "-receiver", Role.MANAGER);
    JsonNode po =
        performOk(
            authed(
                json(
                    post("/api/procurement/purchase-orders"),
                    Map.of(
                        "companyId", companyId,
                        "supplierId", supplierId,
                        "plantId", plantId,
                        "currency", "CNY",
                        "lines", List.of(Map.of("itemId", itemId, "qty", 10, "uom", "EA", "price", 100)))),
                buyer));
    long poId = po.path("id").asLong();
    JsonNode approved =
        performOk(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/approve"), Map.of("version", 0)),
                approver));
    performOk(
        authed(
            json(
                post("/api/procurement/purchase-orders/" + poId + "/send"),
                Map.of("version", approved.path("version").asInt())),
            approver));
    return new Setup(companyId, plantId, warehouseId, fromLocationId, toLocationId, itemId, poId, receiver);
  }

  private void receive(Setup setup, int qty) throws Exception {
    long poLineId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM purchase_order_line WHERE po_id = ? ORDER BY id LIMIT 1",
            Long.class,
            setup.poId());
    performOk(
        authed(
            json(
                post("/api/procurement/goods-receipts"),
                Map.of(
                    "poId", setup.poId(),
                    "warehouseId", setup.warehouseId(),
                    "lines", List.of(Map.of(
                        "poLineId", poLineId,
                        "qty", qty,
                        "locationId", setup.fromLocationId())))),
            setup.receiver()));
  }

  private BigDecimal stockField(Setup setup, String column) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(" + column + "), 0) FROM inventory_stock WHERE company_id = ?"
            + " AND warehouse_id = ? AND item_id = ? AND location_id = ?",
        BigDecimal.class,
        setup.companyId(),
        setup.warehouseId(),
        setup.itemId(),
        setup.fromLocationId());
  }

  private BigDecimal stockAtLocation(Setup setup, long locationId) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(qty_on_hand), 0) FROM inventory_stock WHERE company_id = ?"
            + " AND warehouse_id = ? AND item_id = ? AND location_id = ?",
        BigDecimal.class,
        setup.companyId(),
        setup.warehouseId(),
        setup.itemId(),
        locationId);
  }

  private long createLocation(String token, long warehouseId, String code) throws Exception {
    return performOk(
        authed(
            json(
                post("/api/masterdata/warehouses/" + warehouseId + "/locations"),
                Map.of("code", code, "name", "库位 " + code)),
            token))
        .path("id")
        .asLong();
  }

  private void configureFinance(long companyId) {
    long inventory = insertAccount(companyId, "1405", "库存商品", "ASSET", "DEBIT");
    long grIr = insertAccount(companyId, "2150", "GR/IR 暂估", "LIABILITY", "CREDIT");
    long inputTax = insertAccount(companyId, "2220", "应交税费-进项", "ASSET", "DEBIT");
    long ap = insertAccount(companyId, "2202", "应付账款", "LIABILITY", "CREDIT");
    long bank = insertAccount(companyId, "1002", "银行存款", "ASSET", "DEBIT");
    insertMapping(companyId, "GOODS_RECEIPT", inventory, grIr, null);
    insertMapping(companyId, "SUPPLIER_INVOICE", grIr, ap, inputTax);
    insertMapping(companyId, "PAYMENT", ap, bank, null);
  }

  private long insertAccount(
      long companyId, String code, String name, String type, String normalBalance) {
    jdbcTemplate.update(
        "INSERT INTO gl_account (company_id, code, name, account_type, normal_balance,"
            + " requires_cost_center, status, version) VALUES (?, ?, ?, ?, ?, 0, 'ACTIVE', 0)",
        companyId,
        code,
        name,
        type,
        normalBalance);
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM gl_account WHERE company_id = ? AND code = ?", Long.class, companyId, code);
    if (id == null) {
      throw new IllegalStateException("科目不存在：" + code);
    }
    return id;
  }

  private void insertMapping(
      long companyId, String eventType, long debit, long credit, Long tax) {
    jdbcTemplate.update(
        "INSERT INTO account_mapping (company_id, event_type, dimension, debit_account_id,"
            + " credit_account_id, tax_account_id, valid_from, status, version)"
            + " VALUES (?, ?, '', ?, ?, ?, ?, 'ACTIVE', 0)",
        companyId,
        eventType,
        debit,
        credit,
        tax,
        java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusYears(1)));
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "OPS 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", "code", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/plants"),
                Map.of("companyId", companyId, "code", code, "name", "OPS 工厂 " + code)),
            token));
    return idOf("plant", "code", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of("plantId", plantId, "code", code, "name", "OPS 仓库 " + code, "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", "code", code);
  }

  private long createItem(
      String token, String code, String valuationMethod, BigDecimal standardCost) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "OPS 物料 " + code);
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("lotControlled", false);
    body.put("serialControlled", false);
    body.put("valuationMethod", valuationMethod);
    body.put("standardCost", standardCost);
    performOk(authed(json(post("/api/masterdata/items"), body), token));
    return idOf("item", "code", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/suppliers"),
                Map.of("code", code, "name", "OPS 供应商 " + code, "category", "电子")),
            token));
    return idOf("supplier", "code", code);
  }

  private long idOf(String table, String column, String value) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM " + table + " WHERE " + column + " = ?", Long.class, value);
    if (id == null) {
      throw new IllegalStateException(table + " 不存在：" + value);
    }
    return id;
  }

  private String loginAs(String adminToken, String username, Role role) throws Exception {
    performOk(
        authed(
            json(
                post("/api/users"),
                Map.of(
                    "username", username,
                    "password", DEFAULT_PASSWORD,
                    "name", "OPS 用例账号",
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
