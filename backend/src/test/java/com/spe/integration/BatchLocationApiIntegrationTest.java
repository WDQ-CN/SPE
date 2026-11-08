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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E1-W3 续：批次/库位主数据、批次收货、库存维度与状态守卫（docs/erp/31、33）。 */
@Transactional
class BatchLocationApiIntegrationTest extends AbstractIntegrationTest {

  private record Setup(
      long companyId,
      long plantId,
      long warehouseId,
      long itemId,
      long supplierId,
      long poId,
      long poLineId,
      String buyer,
      String receiver) {}

  private Setup setup(boolean lotControlled) throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "BL-CO-1");
    long plantId = createPlant(admin, companyId, "BL-PL-1");
    long warehouseId = createWarehouse(admin, plantId, "BL-WH-1");
    long itemId = createItem(admin, lotControlled ? "BL-IT-LOT" : "BL-IT-NOLOT", lotControlled);
    long supplierId = createSupplier(admin, "BL-SUP-1");
    configureFinance(companyId);
    String buyer = loginAs(admin, "bl-buyer", Role.MANAGER);
    String poApprover = loginAs(admin, "bl-po-approver", Role.ADMIN);
    String receiver = loginAs(admin, "bl-receiver", Role.MANAGER);
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
    long poLineId = po.path("lines").get(0).path("id").asLong();
    JsonNode approved =
        performOk(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/approve"), Map.of("version", 0)),
                poApprover));
    performOk(
        authed(
            json(post("/api/procurement/purchase-orders/" + poId + "/send"), Map.of("version", approved.path("version").asInt())),
            poApprover));
    return new Setup(companyId, plantId, warehouseId, itemId, supplierId, poId, poLineId, buyer, receiver);
  }

  private JsonNode createLocation(String token, long warehouseId, String code) throws Exception {
    return performOk(
        authed(
            json(
                post("/api/masterdata/warehouses/" + warehouseId + "/locations"),
                Map.of(
                    "code", code,
                    "name", "库位 " + code,
                    "zone", "A",
                    "aisle", "01",
                    "rack", "01",
                    "levelNo", "1",
                    "binNo", "01")),
            token));
  }

  private JsonNode receive(
      Setup setup, String qty, String lotNo, Long locationId) throws Exception {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("poLineId", setup.poLineId());
    line.put("qty", new BigDecimal(qty));
    if (lotNo != null) {
      line.put("lotNo", lotNo);
      line.put("productionDate", LocalDate.now().minusDays(10).toString());
      line.put("expiryDate", LocalDate.now().plusYears(1).toString());
    }
    if (locationId != null) {
      line.put("locationId", locationId);
    }
    return performOk(
        authed(
            json(
                post("/api/procurement/goods-receipts"),
                Map.of(
                    "poId", setup.poId(),
                    "warehouseId", setup.warehouseId(),
                    "lines", List.of(line))),
            setup.receiver()));
  }

  @Test
  @DisplayName("库位：创建/列表/唯一键/更新/停用与审计")
  void locationMasterCrud() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "BL-CO-LOC");
    long plantId = createPlant(admin, companyId, "BL-PL-LOC");
    long warehouseId = createWarehouse(admin, plantId, "BL-WH-LOC");

    JsonNode created = createLocation(admin, warehouseId, "A-01-01-1-01");
    assertThat(created.path("status").asText()).isEqualTo("ACTIVE");
    long locationId = created.path("id").asLong();

    JsonNode list =
        performOk(
            authed(
                get("/api/masterdata/warehouses/" + warehouseId + "/locations"), admin));
    assertThat(list).hasSize(1);
    assertThat(list.get(0).path("code").asText()).isEqualTo("A-01-01-1-01");

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/warehouses/" + warehouseId + "/locations"),
                    Map.of("code", "A-01-01-1-01", "name", "重复库位")),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2127));

    JsonNode updated =
        performOk(
            authed(
                json(
                    put("/api/masterdata/locations/" + locationId),
                    Map.of(
                        "code", "A-01-01-1-01",
                        "name", "主收货库位",
                        "zone", "A",
                        "aisle", "01",
                        "rack", "01",
                        "levelNo", "1",
                        "binNo", "02",
                        "version", 0)),
                admin));
    assertThat(updated.path("name").asText()).isEqualTo("主收货库位");
    assertThat(updated.path("binNo").asText()).isEqualTo("02");

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/masterdata/locations/" + locationId + "/status"),
                    Map.of("status", "INACTIVE", "version", 1)),
                admin))
        .andExpect(status().isOk());
    assertThat(auditRows("WAREHOUSE_LOCATION_CREATE")).hasSize(1);
    assertThat(auditRows("WAREHOUSE_LOCATION_UPDATE")).hasSize(1);
    assertThat(auditRows("WAREHOUSE_LOCATION_STATUS_CHANGE")).hasSize(1);
  }

  @Test
  @DisplayName("批次收货：批次必填、库存按批次/库位、批次隔离阻断继续收货、冲销回退")
  void lotControlledReceiptAndStockDimension() throws Exception {
    Setup setup = setup(true);
    long locationId = createLocation(setup.buyer(), setup.warehouseId(), "LOT-BIN-01").path("id").asLong();

    // 批次管理物料缺批次 → 2302
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    Map.of(
                        "poId", setup.poId(),
                        "warehouseId", setup.warehouseId(),
                        "lines", List.of(Map.of("poLineId", setup.poLineId(), "qty", 1, "locationId", locationId)))),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2302));

    JsonNode receipt = receive(setup, "4", "LOT-2026-001", locationId);
    assertThat(receipt.path("status").asText()).isEqualTo("POSTED");
    long receiptId = receipt.path("id").asLong();

    JsonNode lots =
        performOk(
            authed(
                get("/api/inventory/lots").param("itemId", String.valueOf(setup.itemId())),
                setup.buyer()));
    assertThat(lots.path("total").asLong()).isEqualTo(1);
    JsonNode lot = lots.path("list").get(0);
    assertThat(lot.path("lotNo").asText()).isEqualTo("LOT-2026-001");
    assertThat(lot.path("status").asText()).isEqualTo("ACTIVE");
    long lotId = lot.path("id").asLong();

    JsonNode stock =
        performOk(
            authed(
                get("/api/inventory/stock")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("warehouseId", String.valueOf(setup.warehouseId()))
                    .param("itemId", String.valueOf(setup.itemId()))
                    .param("lotId", String.valueOf(lotId))
                    .param("locationId", String.valueOf(locationId)),
                setup.buyer()));
    assertThat(stock.path("total").asLong()).isEqualTo(1);
    assertThat(stock.path("list").get(0).path("lotNo").asText()).isEqualTo("LOT-2026-001");
    assertThat(stock.path("list").get(0).path("locationName").asText()).isEqualTo("库位 LOT-BIN-01");
    assertThat(stock.path("list").get(0).path("qtyOnHand").decimalValue())
        .isEqualByComparingTo("4.000000");

    JsonNode movements =
        performOk(
            authed(
                get("/api/inventory/movements")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("lotId", String.valueOf(lotId))
                    .param("locationId", String.valueOf(locationId)),
                setup.buyer()));
    assertThat(movements.path("total").asLong()).isEqualTo(1);
    assertThat(movements.path("list").get(0).path("lotNo").asText()).isEqualTo("LOT-2026-001");
    assertThat(movements.path("list").get(0).path("locationName").asText()).isEqualTo("库位 LOT-BIN-01");

    // 批次隔离后不能继续收货
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/inventory/lots/" + lotId + "/status"),
                    Map.of("status", "QUARANTINE", "version", 0)),
                setup.buyer()))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    Map.of(
                        "poId", setup.poId(),
                        "warehouseId", setup.warehouseId(),
                        "lines", List.of(Map.of("poLineId", setup.poLineId(), "qty", 1, "lotNo", "LOT-2026-001", "locationId", locationId)))),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2304));

    // 冲销第一张 GR：库存回到 0，批次仍保留
    performOk(
        authed(
            json(
                post("/api/procurement/goods-receipts/" + receiptId + "/reverse"),
                Map.of("reason", "批次隔离后整批退回", "version", receipt.path("version").asInt())),
            setup.receiver()));
    JsonNode after =
        performOk(
            authed(
                get("/api/inventory/stock")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("lotId", String.valueOf(lotId))
                    .param("locationId", String.valueOf(locationId)),
                setup.buyer()));
    assertThat(after.path("list").get(0).path("qtyOnHand").decimalValue())
        .isEqualByComparingTo("0.000000");
    assertThat(auditRows("INVENTORY_LOT_STATUS_CHANGE")).hasSize(1);
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
    insertMapping(companyId, "PURCHASE_RETURN_UNINVOICED", grIr, inventory, null);
    insertMapping(companyId, "PURCHASE_RETURN_INVOICED", ap, inventory, null);
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
        java.sql.Date.valueOf(LocalDate.now().minusYears(1)));
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "BL 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/masterdata/plants"), Map.of("companyId", companyId, "code", code, "name", "BL 工厂 " + code)),
            token));
    return idOf("plant", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of("plantId", plantId, "code", code, "name", "BL 仓库 " + code, "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", code);
  }

  private long createItem(String token, String code, boolean lotControlled) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "BL 物料 " + code);
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("lotControlled", lotControlled);
    body.put("serialControlled", false);
    body.put("valuationMethod", "AVCO");
    performOk(authed(json(post("/api/masterdata/items"), body), token));
    return idOf("item", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/suppliers"), Map.of("code", code, "name", "BL 供应商 " + code, "category", "电子")),
            token));
    return idOf("supplier", code);
  }

  private long idOf(String table, String code) {
    Long id = jdbcTemplate.queryForObject("SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException(table + " 不存在：" + code);
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
                    "name", "BL 用例账号",
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
