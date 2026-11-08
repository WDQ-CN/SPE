package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.api.Role;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

/** E2 W1：序列号收货/追溯、FEFO、效期预警（docs/erp/54）。 */
@Transactional
class SerialFefoApiIntegrationTest extends AbstractIntegrationTest {

  private record Setup(
      long companyId,
      long plantId,
      long warehouseId,
      long itemId,
      long supplierId,
      long poId,
      long poLineId,
      String receiver,
      long locationId) {}

  private Setup setup() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "SF-CO-1");
    long plantId = createPlant(admin, companyId, "SF-PL-1");
    long warehouseId = createWarehouse(admin, plantId, "SF-WH-1");
    long itemId = createItem(admin, "SF-IT-1");
    long supplierId = createSupplier(admin, "SF-SUP-1");
    configureFinance(companyId);
    long locationId = createLocation(admin, warehouseId, "SF-BIN-1");
    String buyer = loginAs(admin, "sf-buyer", Role.MANAGER);
    String poApprover = loginAs(admin, "sf-po-approver", Role.ADMIN);
    String receiver = loginAs(admin, "sf-receiver", Role.MANAGER);
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
                        "lines", List.of(Map.of("itemId", itemId, "qty", 3, "uom", "EA", "price", 100)))),
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
    return new Setup(companyId, plantId, warehouseId, itemId, supplierId, poId, poLineId, receiver, locationId);
  }

  @Test
  @DisplayName("序列收货/追溯、FEFO、效期预警与冲销序列状态")
  void serialFefoAndExpiry() throws Exception {
    Setup setup = setup();

    // 序列物料缺序列 → 2306
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    Map.of(
                        "poId", setup.poId(),
                        "warehouseId", setup.warehouseId(),
                        "lines", List.of(Map.of(
                            "poLineId", setup.poLineId(),
                            "qty", 2,
                            "lotNo", "LOT-A",
                            "locationId", setup.locationId())))),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2306));

    // 序列数量与数量不一致 → 2306；重复序列 → 2307
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    receiptBody(setup, 2, "LOT-A", List.of("S-1"))),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2306));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    receiptBody(setup, 2, "LOT-A", List.of("S-1", "S-1"))),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2307));

    JsonNode first =
        performOk(
            authed(
                json(post("/api/procurement/goods-receipts"), receiptBody(setup, 2, "LOT-A", List.of("S-1", "S-2"))),
                setup.receiver()));
    JsonNode second =
        performOk(
            authed(
                json(post("/api/procurement/goods-receipts"), receiptBody(setup, 1, "LOT-B", List.of("S-3"))),
                setup.receiver()));
    assertThat(first.path("status").asText()).isEqualTo("POSTED");
    assertThat(second.path("status").asText()).isEqualTo("POSTED");

    JsonNode serials =
        performOk(
            authed(
                get("/api/inventory/serials").param("itemId", String.valueOf(setup.itemId())),
                setup.receiver()));
    assertThat(serials.path("total").asLong()).isEqualTo(3);
    long serialId = idOf("inventory_serial", "serial_no", "S-1");
    JsonNode trace =
        performOk(authed(get("/api/inventory/serials/" + serialId + "/trace"), setup.receiver()));
    assertThat(trace.path("movements").size()).isEqualTo(1);
    assertThat(trace.path("movements").get(0).path("direction").asText()).isEqualTo("IN");

    // FEFO 优先 LOT-A（到期更早）
    JsonNode fefo =
        performOk(
            authed(
                get("/api/inventory/lots/fefo")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("warehouseId", String.valueOf(setup.warehouseId()))
                    .param("itemId", String.valueOf(setup.itemId()))
                    .param("qty", "2"),
                setup.receiver()));
    assertThat(fefo.get(0).path("lotNo").asText()).isEqualTo("LOT-A");
    assertThat(fefo.get(0).path("suggestedQty").decimalValue()).isEqualByComparingTo("2.000000");

    JsonNode expiring =
        performOk(
            authed(
                get("/api/inventory/lots/expiring").param("days", "30"),
                setup.receiver()));
    assertThat(expiring).hasSize(1);
    assertThat(expiring.get(0).path("lotNo").asText()).isEqualTo("LOT-A");

    // 预警去重
    JsonNode scan =
        performOk(authed(post("/api/inventory/lots/expiry-scan").param("days", "30"), setup.receiver()));
    assertThat(scan.asInt()).isEqualTo(1);
    JsonNode scanAgain =
        performOk(authed(post("/api/inventory/lots/expiry-scan").param("days", "30"), setup.receiver()));
    assertThat(scanAgain.asInt()).isEqualTo(0);

    // 冲销第一张 GR：S-1/S-2 置 RETURNED，库存只剩 LOT-B
    performOk(
        authed(
            json(
                post("/api/procurement/goods-receipts/" + first.path("id").asLong() + "/reverse"),
                Map.of("reason", "序列整批退回", "version", first.path("version").asInt())),
            setup.receiver()));
    assertThat(serialStatus("S-1")).isEqualTo("RETURNED");
    assertThat(serialStatus("S-2")).isEqualTo("RETURNED");
    assertThat(stockQty(setup)).isEqualByComparingTo("1.000000");

    mockMvc
        .perform(
            authed(
                get("/api/inventory/lots/fefo")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("warehouseId", String.valueOf(setup.warehouseId()))
                    .param("itemId", String.valueOf(setup.itemId()))
                    .param("qty", "2"),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2314));
  }

  private Map<String, Object> receiptBody(
      Setup setup, int qty, String lotNo, List<String> serials) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("poLineId", setup.poLineId());
    line.put("qty", qty);
    line.put("lotNo", lotNo);
    line.put("locationId", setup.locationId());
    line.put("productionDate", LocalDate.now(ZoneOffset.UTC).minusDays(5).toString());
    line.put(
        "expiryDate",
        (lotNo.equals("LOT-A")
                ? LocalDate.now(ZoneOffset.UTC).plusDays(10)
                : LocalDate.now(ZoneOffset.UTC).plusDays(100))
            .toString());
    line.put("serials", serials);
    return Map.of("poId", setup.poId(), "warehouseId", setup.warehouseId(), "lines", List.of(line));
  }

  private String serialStatus(String serialNo) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM inventory_serial WHERE serial_no = ?", String.class, serialNo);
  }

  private BigDecimal stockQty(Setup setup) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(qty_on_hand), 0) FROM inventory_stock WHERE company_id = ? AND item_id = ?",
        BigDecimal.class,
        setup.companyId(),
        setup.itemId());
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
                Map.of("code", code, "name", "SF 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", "code", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/masterdata/plants"), Map.of("companyId", companyId, "code", code, "name", "SF 工厂 " + code)),
            token));
    return idOf("plant", "code", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of("plantId", plantId, "code", code, "name", "SF 仓库 " + code, "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", "code", code);
  }

  private long createItem(String token, String code) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "SF 物料 " + code);
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("lotControlled", true);
    body.put("serialControlled", true);
    body.put("valuationMethod", "AVCO");
    performOk(authed(json(post("/api/masterdata/items"), body), token));
    return idOf("item", "code", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/suppliers"), Map.of("code", code, "name", "SF 供应商 " + code, "category", "电子")),
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
                    "name", "SF 用例账号",
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
