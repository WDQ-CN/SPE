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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E1-W3：收货生成库存台账、库存查询、收货冲销与库存/发票守卫（docs/erp/33、52）。 */
@Transactional
class InventoryApiIntegrationTest extends AbstractIntegrationTest {

  private record Setup(
      long companyId,
      long plantId,
      long warehouseId,
      long itemId,
      long supplierId,
      String buyer,
      String poApprover,
      String receiver) {}

  private Setup setup() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "W3-CO-1");
    long plantId = createPlant(admin, companyId, "W3-PL-1");
    long warehouseId = createWarehouse(admin, plantId, "W3-WH-1");
    long itemId = createItem(admin, "W3-IT-1");
    long supplierId = createSupplier(admin, "W3-SUP-1");
    configureFinance(companyId);
    return new Setup(
        companyId,
        plantId,
        warehouseId,
        itemId,
        supplierId,
        loginAs(admin, "w3-buyer", Role.MANAGER),
        loginAs(admin, "w3-po-approver", Role.ADMIN),
        loginAs(admin, "w3-receiver", Role.MANAGER));
  }

  private JsonNode receiveAll(Setup setup, long qty) throws Exception {
    return receive(setup, qty, 100);
  }

  private JsonNode receive(Setup setup, long qty, int price) throws Exception {
    JsonNode po =
        performOk(
            authed(
                json(
                    post("/api/procurement/purchase-orders"),
                    Map.of(
                        "companyId", setup.companyId(),
                        "supplierId", setup.supplierId(),
                        "plantId", setup.plantId(),
                        "currency", "CNY",
                        "lines", List.of(Map.of("itemId", setup.itemId(), "qty", qty, "uom", "EA", "price", price)))),
                setup.buyer()));
    long poId = po.path("id").asLong();
    long poLineId = po.path("lines").get(0).path("id").asLong();
    JsonNode approved =
        performOk(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/approve"), Map.of("version", 0)),
                setup.poApprover()));
    performOk(
        authed(
            json(post("/api/procurement/purchase-orders/" + poId + "/send"), Map.of("version", approved.path("version").asInt())),
            setup.poApprover()));
    JsonNode receipt =
        performOk(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    Map.of(
                        "poId", poId,
                        "warehouseId", setup.warehouseId(),
                        "lines", List.of(Map.of("poLineId", poLineId, "qty", qty)))),
                setup.receiver()));
    return receipt;
  }

  @Test
  @DisplayName("收货生成 RECEIPT 库存；冲销生成 REVERSAL、回退 PO 状态与库存")
  void receiptAndReverse() throws Exception {
    Setup setup = setup();
    JsonNode receipt = receiveAll(setup, 10);
    long receiptId = receipt.path("id").asLong();
    assertThat(receipt.path("status").asText()).isEqualTo("POSTED");

    JsonNode stock =
        performOk(
            authed(
                get("/api/inventory/stock")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("warehouseId", String.valueOf(setup.warehouseId()))
                    .param("itemId", String.valueOf(setup.itemId())),
                setup.buyer()));
    assertThat(stock.path("total").asLong()).isEqualTo(1);
    JsonNode stockRow = stock.path("list").get(0);
    assertThat(stockRow.path("qtyOnHand").decimalValue()).isEqualByComparingTo("10.000000");
    assertThat(stockRow.path("totalValue").decimalValue()).isEqualByComparingTo("1000.0000");
    assertThat(stockRow.path("avgUnitCost").decimalValue()).isEqualByComparingTo("100.0000");

    JsonNode movements =
        performOk(
            authed(
                get("/api/inventory/movements")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("sourceType", "GOODS_RECEIPT")
                    .param("sourceId", String.valueOf(receipt.path("id").asLong())),
                setup.buyer()));
    assertThat(movements.path("total").asLong()).isEqualTo(1);
    assertThat(movements.path("list").get(0).path("movementType").asText()).isEqualTo("RECEIPT");
    assertThat(movements.path("list").get(0).path("direction").asText()).isEqualTo("IN");

    JsonNode reversed =
        performOk(
            authed(
                json(
                    post("/api/procurement/goods-receipts/" + receiptId + "/reverse"),
                    Map.of("reason", "收货数量录入错误", "version", receipt.path("version").asInt())),
                setup.receiver()));
    assertThat(reversed.path("status").asText()).isEqualTo("REVERSED");
    assertThat(reversed.path("reverseReason").asText()).isEqualTo("收货数量录入错误");

    JsonNode afterStock =
        performOk(
            authed(
                get("/api/inventory/stock")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("warehouseId", String.valueOf(setup.warehouseId()))
                    .param("itemId", String.valueOf(setup.itemId())),
                setup.buyer()));
    assertThat(afterStock.path("list").get(0).path("qtyOnHand").decimalValue())
        .isEqualByComparingTo("0.000000");
    assertThat(afterStock.path("list").get(0).path("totalValue").decimalValue())
        .isEqualByComparingTo("0.0000");

    JsonNode afterMovements =
        performOk(
            authed(
                get("/api/inventory/movements")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("sourceType", "GOODS_RECEIPT")
                    .param("sourceId", String.valueOf(receiptId)),
                setup.buyer()));
    assertThat(afterMovements.path("total").asLong()).isEqualTo(2);
    assertThat(afterMovements.path("list").get(0).path("movementType").asText()).isEqualTo("REVERSAL");
    assertThat(afterMovements.path("list").get(0).path("direction").asText()).isEqualTo("OUT");

    // 冲销后 PO 回到 SENT，已收数量归零
    JsonNode po =
        performOk(
            authed(
                get("/api/procurement/purchase-orders/" + reversed.path("poId").asLong()),
                setup.buyer()));
    assertThat(po.path("status").asText()).isEqualTo("SENT");
    assertThat(po.path("lines").get(0).path("receivedQty").decimalValue())
        .isEqualByComparingTo("0.000000");

    // 已冲销的 GR 不能再冲销
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts/" + receiptId + "/reverse"),
                    Map.of("reason", "重复冲销", "version", reversed.path("version").asInt())),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2230));

    assertThat(auditRows("GOODS_RECEIPT_REVERSE")).hasSize(1);
    assertThat(auditRows("INVENTORY_MOVEMENT_POST")).hasSize(2);
  }

  @Test
  @DisplayName("移动平均：10@100 + 10@200 后均价 150；冲销第二笔回到 100")
  void movingAverageAcrossReceipts() throws Exception {
    Setup setup = setup();
    receiveAll(setup, 10);
    JsonNode second = receive(setup, 10, 200);

    JsonNode stock = stockOf(setup);
    assertThat(stock.path("qtyOnHand").decimalValue()).isEqualByComparingTo("20.000000");
    assertThat(stock.path("totalValue").decimalValue()).isEqualByComparingTo("3000.0000");
    assertThat(stock.path("avgUnitCost").decimalValue()).isEqualByComparingTo("150.0000");

    performOk(
        authed(
            json(
                post("/api/procurement/goods-receipts/" + second.path("id").asLong() + "/reverse"),
                Map.of("reason", "第二笔收货退回供应商", "version", second.path("version").asInt())),
            setup.receiver()));

    JsonNode after = stockOf(setup);
    assertThat(after.path("qtyOnHand").decimalValue()).isEqualByComparingTo("10.000000");
    assertThat(after.path("totalValue").decimalValue()).isEqualByComparingTo("1000.0000");
    assertThat(after.path("avgUnitCost").decimalValue()).isEqualByComparingTo("100.0000");
  }

  private JsonNode stockOf(Setup setup) throws Exception {
    JsonNode page =
        performOk(
            authed(
                get("/api/inventory/stock")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("warehouseId", String.valueOf(setup.warehouseId()))
                    .param("itemId", String.valueOf(setup.itemId())),
                setup.buyer()));
    return page.path("list").get(0);
  }

  @Test
  @DisplayName("已开票的收货不能冲销 2229；越权查库存 403")
  void reverseBlockedByInvoiceAndPermissions() throws Exception {
    Setup setup = setup();
    JsonNode receipt = receiveAll(setup, 10);
    long poId = receipt.path("poId").asLong();
    long poLineId = receipt.path("lines").get(0).path("poLineId").asLong();

    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    String ap = loginAs(admin, "w3-ap", Role.MANAGER);
    String invoiceApprover = loginAs(admin, "w3-inv-appr", Role.MANAGER);
    JsonNode invoice =
        performOk(
            authed(
                json(
                    post("/api/procurement/invoices"),
                    Map.of(
                        "poId", poId,
                        "supplierInvoiceNo", "W3-INV-1",
                        "invoiceDate", LocalDate.now().toString(),
                        "currency", "CNY",
                        "taxAmount", 0,
                        "lines", List.of(Map.of("poLineId", poLineId, "qty", 10, "unitPrice", 100)))),
                ap));
    performOk(
        authed(
            json(post("/api/procurement/invoices/" + invoice.path("id").asLong() + "/approve"), Map.of("version", invoice.path("version").asInt())),
            invoiceApprover));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts/" + receipt.path("id").asLong() + "/reverse"),
                    Map.of("reason", "已开票仍试图冲销", "version", receipt.path("version").asInt())),
                setup.receiver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2229));

    String evaluator = loginAs(admin, "w3-eval", Role.EVALUATOR);
    mockMvc
        .perform(authed(get("/api/inventory/stock"), evaluator))
        .andExpect(status().isForbidden());
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
        java.sql.Date.valueOf(LocalDate.now().minusDays(1)));
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "W3 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/masterdata/plants"), Map.of("companyId", companyId, "code", code, "name", "W3 工厂 " + code)),
            token));
    return idOf("plant", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of("plantId", plantId, "code", code, "name", "W3 仓库 " + code, "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", code);
  }

  private long createItem(String token, String code) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "W3 物料 " + code);
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("valuationMethod", "AVCO");
    performOk(authed(json(post("/api/masterdata/items"), body), token));
    return idOf("item", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/suppliers"), Map.of("code", code, "name", "W3 供应商 " + code, "category", "电子")),
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
                    "name", "W3 用例账号",
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
