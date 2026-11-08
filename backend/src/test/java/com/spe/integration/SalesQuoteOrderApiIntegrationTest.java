package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** E3 销售：报价/订单/信用校验（docs/erp/35）。 */
@Transactional
class SalesQuoteOrderApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("报价转订单、信用校验、直接订单确认与取消")
  void quoteAndOrderLifecycle() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "SO-CO");
    long customerId = createCustomer(admin, "SO-CUS", 1000);
    long itemId = createItem(admin, "SO-IT");

    JsonNode quote =
        performOk(
            authed(
                json(
                    post("/api/sales/quotes"),
                    Map.of(
                        "companyId", companyId,
                        "customerId", customerId,
                        "currency", "CNY",
                        "validUntil", LocalDate.now(ZoneOffset.UTC).plusDays(10).toString(),
                        "lines", List.of(
                            Map.of(
                                "itemId", itemId,
                                "qty", 10,
                                "uom", "EA",
                                "unitPrice", 100,
                                "discountRate", 0.1,
                                "taxRate", 0.13)))),
                admin));
    long quoteId = quote.path("id").asLong();
    assertThat(quote.path("status").asText()).isEqualTo("DRAFT");
    assertThat(quote.path("netAmount").decimalValue()).isEqualByComparingTo("900.0000");
    assertThat(quote.path("taxAmount").decimalValue()).isEqualByComparingTo("117.0000");
    assertThat(quote.path("totalAmount").decimalValue()).isEqualByComparingTo("1017.0000");

    performOk(authed(post("/api/sales/quotes/" + quoteId + "/send"), admin));
    JsonNode order =
        performOk(authed(post("/api/sales/quotes/" + quoteId + "/accept"), admin));
    assertThat(order.path("status").asText()).isEqualTo("DRAFT");
    assertThat(order.path("sourceQuoteId").asLong()).isEqualTo(quoteId);
    assertThat(order.path("totalAmount").decimalValue()).isEqualByComparingTo("1017.0000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT converted_order_id FROM sales_quote WHERE id = ?", Long.class, quoteId))
        .isEqualTo(order.path("id").asLong());

    // 信用额度 1000 < 1017 → 2505
    long quoteOrderId = order.path("id").asLong();
    mockMvc
        .perform(authed(post("/api/sales/orders/" + quoteOrderId + "/confirm"), admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2505));

    // 直接订单：508.5 ≤ 1000 → 确认成功
    JsonNode directOrder =
        performOk(
            authed(
                json(
                    post("/api/sales/orders"),
                    Map.of(
                        "companyId", companyId,
                        "customerId", customerId,
                        "currency", "CNY",
                        "lines", List.of(
                            Map.of(
                                "itemId", itemId,
                                "qty", 5,
                                "uom", "EA",
                                "unitPrice", 100,
                                "discountRate", 0.1,
                                "taxRate", 0.13)))),
                admin));
    assertThat(directOrder.path("totalAmount").decimalValue()).isEqualByComparingTo("508.5000");
    long orderId = directOrder.path("id").asLong();
    JsonNode confirmed =
        performOk(authed(post("/api/sales/orders/" + orderId + "/confirm"), admin));
    assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
    JsonNode canceled =
        performOk(authed(post("/api/sales/orders/" + orderId + "/cancel"), admin));
    assertThat(canceled.path("status").asText()).isEqualTo("CANCELED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sales_order_line WHERE order_id = ?", Long.class, orderId))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("销售发货过账：库存出库、订单已发数量与部分/全部发货状态")
  void shipmentPostsInventory() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "SHP-CO");
    long plantId = createPlant(admin, companyId, "SHP-PL");
    long warehouseId = createWarehouse(admin, plantId, "SHP-WH");
    long locationId = createLocation(admin, warehouseId, "SHP-BIN");
    long customerId = createCustomer(admin, "SHP-CUS", 2000);
    long itemId = createItem(admin, "SHP-IT");
    configureSalesFinance(companyId);
    jdbcTemplate.update(
        "INSERT INTO inventory_stock (company_id, plant_id, warehouse_id, item_id, lot_id,"
            + " location_id, qty_on_hand, qty_reserved, qty_frozen, total_value, avg_unit_cost,"
            + " version) VALUES (?, ?, ?, ?, 0, ?, 10, 0, 0, 600, 60, 0)",
        companyId,
        plantId,
        warehouseId,
        itemId,
        locationId);
    JsonNode order =
        performOk(
            authed(
                json(
                    post("/api/sales/orders"),
                    Map.of(
                        "companyId", companyId,
                        "customerId", customerId,
                        "currency", "CNY",
                        "lines", List.of(Map.of(
                            "itemId", itemId,
                            "qty", 10,
                            "uom", "EA",
                            "unitPrice", 100,
                            "taxRate", 0.0)))),
                admin));
    long orderId = order.path("id").asLong();
    long orderLineId = order.path("lines").get(0).path("id").asLong();
    performOk(authed(post("/api/sales/orders/" + orderId + "/confirm"), admin));

    // 超发 11 → 2508
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/sales/shipments"),
                    Map.of(
                        "orderId", orderId,
                        "warehouseId", warehouseId,
                        "lines", List.of(Map.of(
                            "orderLineId", orderLineId,
                            "qty", 11,
                            "locationId", locationId)))),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2508));

    JsonNode shipment =
        performOk(
            authed(
                json(
                    post("/api/sales/shipments"),
                    Map.of(
                        "orderId", orderId,
                        "warehouseId", warehouseId,
                        "lines", List.of(Map.of(
                            "orderLineId", orderLineId,
                            "qty", 4,
                            "locationId", locationId)))),
                admin));
    assertThat(shipment.path("status").asText()).isEqualTo("SHIPPED");
    assertThat(shipment.path("lines").get(0).path("qty").decimalValue())
        .isEqualByComparingTo("4.000000");
    assertThat(stockQty(companyId, itemId)).isEqualByComparingTo("6.000000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_movement WHERE source_type = 'SALES_SHIPMENT'"
                    + " AND movement_type = 'ISSUE' AND source_id = ?",
                Long.class,
                shipment.path("id").asLong()))
        .isEqualTo(1L);
    JsonNode partial =
        performOk(authed(get("/api/sales/orders/" + orderId), admin));
    assertThat(partial.path("status").asText()).isEqualTo("PARTIAL_SHIPPED");

    performOk(
        authed(
            json(
                post("/api/sales/shipments"),
                Map.of(
                    "orderId", orderId,
                    "warehouseId", warehouseId,
                    "lines", List.of(Map.of(
                        "orderLineId", orderLineId,
                        "qty", 6,
                        "locationId", locationId)))),
            admin));
    JsonNode shipped = performOk(authed(get("/api/sales/orders/" + orderId), admin));
    assertThat(shipped.path("status").asText()).isEqualTo("SHIPPED");
    assertThat(stockQty(companyId, itemId)).isEqualByComparingTo("0.000000");
  }

  @Test
  @DisplayName("销售开票、信用占用、部分/全额收款、AR 账龄与财务过账")
  void invoiceAndReceipt() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "INV-CO");
    long plantId = createPlant(admin, companyId, "INV-PL");
    long warehouseId = createWarehouse(admin, plantId, "INV-WH");
    long locationId = createLocation(admin, warehouseId, "INV-BIN");
    long customerId = createCustomer(admin, "INV-CUS", 5000);
    long itemId = createItem(admin, "INV-IT");
    jdbcTemplate.update(
        "INSERT INTO inventory_stock (company_id, plant_id, warehouse_id, item_id, lot_id,"
            + " location_id, qty_on_hand, qty_reserved, qty_frozen, total_value, avg_unit_cost,"
            + " version) VALUES (?, ?, ?, ?, 0, ?, 10, 0, 0, 600, 60, 0)",
        companyId,
        plantId,
        warehouseId,
        itemId,
        locationId);
    configureSalesFinance(companyId);
    JsonNode order =
        performOk(
            authed(
                json(
                    post("/api/sales/orders"),
                    Map.of(
                        "companyId", companyId,
                        "customerId", customerId,
                        "currency", "CNY",
                        "lines", List.of(Map.of(
                            "itemId", itemId,
                            "qty", 10,
                            "uom", "EA",
                            "unitPrice", 100,
                            "taxRate", 0.13)))),
                admin));
    long orderId = order.path("id").asLong();
    long orderLineId = order.path("lines").get(0).path("id").asLong();
    performOk(authed(post("/api/sales/orders/" + orderId + "/confirm"), admin));
    performOk(
        authed(
            json(
                post("/api/sales/shipments"),
                Map.of(
                    "orderId", orderId,
                    "warehouseId", warehouseId,
                    "lines", List.of(Map.of(
                        "orderLineId", orderLineId,
                        "qty", 10,
                        "locationId", locationId)))),
            admin));

    JsonNode invoice =
        performOk(
            authed(
                json(
                    post("/api/sales/invoices"),
                    Map.of(
                        "orderId", orderId,
                        "dueDate", LocalDate.now(ZoneOffset.UTC).toString(),
                        "lines", List.of(Map.of("orderLineId", orderLineId, "qty", 10)))),
                admin));
    long invoiceId = invoice.path("id").asLong();
    assertThat(invoice.path("totalAmount").decimalValue()).isEqualByComparingTo("1130.0000");
    assertThat(invoice.path("status").asText()).isEqualTo("ISSUED");
    assertThat(creditUsed(customerId)).isEqualByComparingTo("1130.0000");
    assertThat(journalCount("SALES_INVOICE")).isEqualTo(1);

    JsonNode firstReceipt =
        performOk(
            authed(
                json(
                    post("/api/sales/receipts"),
                    Map.of(
                        "customerId", customerId,
                        "method", "BANK",
                        "allocations", List.of(
                            Map.of("invoiceId", invoiceId, "amount", 500)))),
                admin));
    assertThat(firstReceipt.path("allocations").get(0).path("amount").decimalValue())
        .isEqualByComparingTo("500.0000");
    JsonNode partial = performOk(authed(get("/api/sales/invoices/" + invoiceId), admin));
    assertThat(partial.path("status").asText()).isEqualTo("PARTIAL");
    assertThat(creditUsed(customerId)).isEqualByComparingTo("630.0000");
    assertThat(journalCount("CUSTOMER_RECEIPT")).isEqualTo(1);

    // 账龄：把到期日改到 40 天前 → 31~60 桶
    jdbcTemplate.update(
        "UPDATE sales_invoice SET due_date = ? WHERE id = ?",
        java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(40)),
        invoiceId);
    JsonNode aging =
        performOk(
            authed(
                get("/api/sales/reports/ar-aging")
                    .param("companyId", String.valueOf(companyId)),
                admin));
    assertThat(aging.path("days31To60").decimalValue()).isEqualByComparingTo("630.0000");
    assertThat(aging.path("rows").get(0).path("bucket").asText()).isEqualTo("31-60");

    performOk(
        authed(
            json(
                post("/api/sales/receipts"),
                Map.of(
                    "customerId", customerId,
                    "method", "BANK",
                    "allocations", List.of(
                        Map.of("invoiceId", invoiceId, "amount", 630)))),
            admin));
    JsonNode paid = performOk(authed(get("/api/sales/invoices/" + invoiceId), admin));
    assertThat(paid.path("status").asText()).isEqualTo("PAID");
    assertThat(creditUsed(customerId)).isEqualByComparingTo("0.0000");
    assertThat(journalCount("CUSTOMER_RECEIPT")).isEqualTo(2);

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/sales/receipts"),
                    Map.of(
                        "customerId", customerId,
                        "allocations", List.of(
                            Map.of("invoiceId", invoiceId, "amount", 1)))),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2512));
  }

  @Test
  @DisplayName("销售退货：按原成本入库、贷项冲减与 COGS 恢复")
  void salesReturnAndCogs() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "RET-CO");
    long plantId = createPlant(admin, companyId, "RET-PL");
    long warehouseId = createWarehouse(admin, plantId, "RET-WH");
    long locationId = createLocation(admin, warehouseId, "RET-BIN");
    long customerId = createCustomer(admin, "RET-CUS", 5000);
    long itemId = createItem(admin, "RET-IT");
    jdbcTemplate.update(
        "INSERT INTO inventory_stock (company_id, plant_id, warehouse_id, item_id, lot_id,"
            + " location_id, qty_on_hand, qty_reserved, qty_frozen, total_value, avg_unit_cost,"
            + " version) VALUES (?, ?, ?, ?, 0, ?, 10, 0, 0, 600, 60, 0)",
        companyId,
        plantId,
        warehouseId,
        itemId,
        locationId);
    configureSalesFinance(companyId);
    JsonNode order =
        performOk(
            authed(
                json(
                    post("/api/sales/orders"),
                    Map.of(
                        "companyId", companyId,
                        "customerId", customerId,
                        "currency", "CNY",
                        "lines", List.of(Map.of(
                            "itemId", itemId,
                            "qty", 10,
                            "uom", "EA",
                            "unitPrice", 100,
                            "taxRate", 0.13)))),
                admin));
    long orderId = order.path("id").asLong();
    long orderLineId = order.path("lines").get(0).path("id").asLong();
    performOk(authed(post("/api/sales/orders/" + orderId + "/confirm"), admin));
    JsonNode shipment =
        performOk(
            authed(
                json(
                    post("/api/sales/shipments"),
                    Map.of(
                        "orderId", orderId,
                        "warehouseId", warehouseId,
                        "lines", List.of(Map.of(
                            "orderLineId", orderLineId,
                            "qty", 10,
                            "locationId", locationId)))),
                admin));
    long shipmentId = shipment.path("id").asLong();
    long shipmentLineId = shipment.path("lines").get(0).path("id").asLong();
    assertThat(stockQty(companyId, itemId)).isEqualByComparingTo("0.000000");
    assertThat(journalCount("SALES_SHIPMENT")).isEqualTo(1);

    JsonNode salesReturn =
        performOk(
            authed(
                json(
                    post("/api/sales/returns"),
                    Map.of(
                        "shipmentId", shipmentId,
                        "reason", "客户退货",
                        "lines", List.of(Map.of(
                            "shipmentLineId", shipmentLineId,
                            "qty", 2,
                            "reason", "外观破损")))),
                admin));
    long returnId = salesReturn.path("id").asLong();
    assertThat(salesReturn.path("status").asText()).isEqualTo("REQUESTED");
    assertThat(salesReturn.path("totalAmount").decimalValue()).isEqualByComparingTo("226.0000");

    JsonNode credited =
        performOk(
            authed(post("/api/sales/returns/" + returnId + "/credit"), admin));
    assertThat(credited.path("status").asText()).isEqualTo("CREDITED");
    assertThat(stockQty(companyId, itemId)).isEqualByComparingTo("2.000000");
    assertThat(stockValue(companyId, itemId)).isEqualByComparingTo("120.0000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT returned_qty FROM sales_shipment_line WHERE id = ?",
                BigDecimal.class,
                shipmentLineId))
        .isEqualByComparingTo("2.000000");
    assertThat(journalCount("SALES_RETURN")).isEqualTo(1);
    assertThat(journalCount("SALES_RETURN_COST")).isEqualTo(1);

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/sales/returns"),
                    Map.of(
                        "shipmentId", shipmentId,
                        "lines", List.of(Map.of(
                            "shipmentLineId", shipmentLineId,
                            "qty", 9)))),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2514));
  }

  private BigDecimal stockValue(long companyId, long itemId) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(total_value), 0) FROM inventory_stock WHERE company_id = ?"
            + " AND item_id = ?",
        BigDecimal.class,
        companyId,
        itemId);
  }

  private BigDecimal creditUsed(long customerId) {
    return jdbcTemplate.queryForObject(
        "SELECT credit_used FROM customer WHERE id = ?", BigDecimal.class, customerId);
  }

  private long journalCount(String sourceType) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM finance_journal_entry WHERE source_type = ?",
        Long.class,
        sourceType);
  }

  private void configureSalesFinance(long companyId) {
    long ar = insertAccount(companyId, "1122", "应收账款", "ASSET", "DEBIT");
    long revenue = insertAccount(companyId, "6001", "主营业务收入", "REVENUE", "CREDIT");
    long outputTax = insertAccount(companyId, "2221", "应交税费-销项", "LIABILITY", "CREDIT");
    long bank = insertAccount(companyId, "1002", "银行存款", "ASSET", "DEBIT");
    long inventory = insertAccount(companyId, "1405", "库存商品", "ASSET", "DEBIT");
    long cogs = insertAccount(companyId, "6401", "主营业务成本", "EXPENSE", "DEBIT");
    insertMapping(companyId, "SALES_INVOICE", ar, revenue, outputTax);
    insertMapping(companyId, "CUSTOMER_RECEIPT", bank, ar, null);
    insertMapping(companyId, "SALES_SHIPMENT", cogs, inventory, null);
    insertMapping(companyId, "SALES_RETURN", revenue, ar, outputTax);
    insertMapping(companyId, "SALES_RETURN_COST", inventory, cogs, null);
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
    return jdbcTemplate.queryForObject(
        "SELECT id FROM gl_account WHERE company_id = ? AND code = ?", Long.class, companyId, code);
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

  private BigDecimal stockQty(long companyId, long itemId) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(qty_on_hand), 0) FROM inventory_stock WHERE company_id = ?"
            + " AND item_id = ?",
        BigDecimal.class,
        companyId,
        itemId);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/plants"),
                Map.of("companyId", companyId, "code", code, "name", "SHP 工厂 " + code)),
            token));
    return idOf("plant", "code", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of("plantId", plantId, "code", code, "name", "SHP 仓库 " + code,
                    "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", "code", code);
  }

  private long createLocation(String token, long warehouseId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses/" + warehouseId + "/locations"),
                Map.of("code", code, "name", "库位 " + code)),
            token));
    return idOf("warehouse_location", "code", code);
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "SO 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", "code", code);
  }

  private long createCustomer(String token, String code, int creditLimit) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/customers"),
                Map.of(
                    "code", code,
                    "name", "SO 客户 " + code,
                    "currency", "CNY",
                    "paymentTerms", "NET30",
                    "creditLimit", creditLimit)),
            token));
    return idOf("customer", "code", code);
  }

  private long createItem(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/items"),
                Map.of(
                    "code", code,
                    "name", "SO 物料 " + code,
                    "itemType", "FINISHED",
                    "baseUom", "EA",
                    "lotControlled", false,
                    "serialControlled", false,
                    "valuationMethod", "AVCO")),
            token));
    return idOf("item", "code", code);
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
