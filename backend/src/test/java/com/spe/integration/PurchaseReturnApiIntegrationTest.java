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

/** E1-W3 续：采购退货、库存出库、可开票量扣减与 AP 贷项对账（docs/erp/32 §5、34 §5.1）。 */
@Transactional
class PurchaseReturnApiIntegrationTest extends AbstractIntegrationTest {

  private record Setup(
      long companyId,
      long plantId,
      long warehouseId,
      long itemId,
      long supplierId,
      long poId,
      long poLineId,
      String buyer,
      String ap,
      String invoiceApprover) {}

  private Setup setup() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "RT-CO-1");
    long plantId = createPlant(admin, companyId, "RT-PL-1");
    long warehouseId = createWarehouse(admin, plantId, "RT-WH-1");
    long itemId = createItem(admin, "RT-IT-1");
    long supplierId = createSupplier(admin, "RT-SUP-1");
    configureFinance(companyId);
    String buyer = loginAs(admin, "rt-buyer", Role.MANAGER);
    String poApprover = loginAs(admin, "rt-po-approver", Role.ADMIN);
    String receiver = loginAs(admin, "rt-receiver", Role.MANAGER);
    String ap = loginAs(admin, "rt-ap", Role.MANAGER);
    String invoiceApprover = loginAs(admin, "rt-inv-appr", Role.MANAGER);

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
    performOk(
        authed(
            json(
                post("/api/procurement/goods-receipts"),
                Map.of(
                    "poId", poId,
                    "warehouseId", warehouseId,
                    "lines", List.of(Map.of("poLineId", poLineId, "qty", 10)))),
            receiver));
    return new Setup(companyId, plantId, warehouseId, itemId, supplierId, poId, poLineId, buyer, ap, invoiceApprover);
  }

  private JsonNode createReturn(Setup setup, String qty, String reason) throws Exception {
    return performOk(
        authed(
            json(
                post("/api/procurement/returns"),
                Map.of(
                    "poId", setup.poId(),
                    "warehouseId", setup.warehouseId(),
                    "returnDate", LocalDate.now().toString(),
                    "reason", reason,
                    "lines", List.of(Map.of("poLineId", setup.poLineId(), "qty", new BigDecimal(qty))))),
            setup.buyer()));
  }

  @Test
  @DisplayName("退货减少库存与可开票量；未开票走 GR/IR，已开票走 AP，AP 子账=GL")
  void purchaseReturnLifecycle() throws Exception {
    Setup setup = setup();
    JsonNode first = createReturn(setup, "4", "来料不合格退回");
    assertThat(first.path("invoiced").asBoolean()).isFalse();
    assertThat(first.path("netAmount").decimalValue()).isEqualByComparingTo("400.0000");

    JsonNode stock =
        performOk(
            authed(
                get("/api/inventory/stock")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("warehouseId", String.valueOf(setup.warehouseId()))
                    .param("itemId", String.valueOf(setup.itemId())),
                setup.buyer()));
    assertThat(stock.path("list").get(0).path("qtyOnHand").decimalValue())
        .isEqualByComparingTo("6.000000");
    assertThat(stock.path("list").get(0).path("totalValue").decimalValue())
        .isEqualByComparingTo("600.0000");

    JsonNode movements =
        performOk(
            authed(
                get("/api/inventory/movements")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("sourceType", "PURCHASE_RETURN")
                    .param("sourceId", String.valueOf(first.path("id").asLong())),
                setup.buyer()));
    assertThat(movements.path("total").asLong()).isEqualTo(1);
    assertThat(movements.path("list").get(0).path("movementType").asText()).isEqualTo("RETURN");
    assertThat(movements.path("list").get(0).path("direction").asText()).isEqualTo("OUT");

    // 10 收货 - 4 退货 = 6 可开票
    JsonNode invoice =
        performOk(
            authed(
                json(
                    post("/api/procurement/invoices"),
                    Map.of(
                        "poId", setup.poId(),
                        "supplierInvoiceNo", "RT-INV-1",
                        "invoiceDate", LocalDate.now().toString(),
                        "currency", "CNY",
                        "taxAmount", 0,
                        "lines", List.of(Map.of("poLineId", setup.poLineId(), "qty", 6, "unitPrice", 100)))),
                setup.ap()));
    assertThat(invoice.path("status").asText()).isEqualTo("MATCHED");
    performOk(
        authed(
            json(post("/api/procurement/invoices/" + invoice.path("id").asLong() + "/approve"), Map.of("version", invoice.path("version").asInt())),
            setup.invoiceApprover()));

    JsonNode second = createReturn(setup, "2", "已开票部分退货");
    assertThat(second.path("invoiced").asBoolean()).isTrue();

    JsonNode afterStock =
        performOk(
            authed(
                get("/api/inventory/stock")
                    .param("companyId", String.valueOf(setup.companyId()))
                    .param("warehouseId", String.valueOf(setup.warehouseId()))
                    .param("itemId", String.valueOf(setup.itemId())),
                setup.buyer()));
    assertThat(afterStock.path("list").get(0).path("qtyOnHand").decimalValue())
        .isEqualByComparingTo("4.000000");

    JsonNode apRec =
        performOk(
            authed(
                get("/api/finance/reports/ap-reconciliation")
                    .param("companyId", String.valueOf(setup.companyId())),
                setup.buyer()));
    assertThat(apRec.path("invoicedTotal").decimalValue()).isEqualByComparingTo("600.0000");
    assertThat(apRec.path("returnedTotal").decimalValue()).isEqualByComparingTo("200.0000");
    assertThat(apRec.path("subledgerBalance").decimalValue()).isEqualByComparingTo("400.0000");
    assertThat(apRec.path("glBalance").decimalValue()).isEqualByComparingTo("400.0000");
    assertThat(apRec.path("difference").decimalValue()).isEqualByComparingTo("0.0000");
    assertThat(apRec.path("reconciled").asBoolean()).isTrue();

    // 净可退 10-4-2=4；再退 5 → 2232
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/returns"),
                    Map.of(
                        "poId", setup.poId(),
                        "warehouseId", setup.warehouseId(),
                        "returnDate", LocalDate.now().toString(),
                        "reason", "超量退货",
                        "lines", List.of(Map.of("poLineId", setup.poLineId(), "qty", 5)))),
                setup.buyer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2232));

    assertThat(auditRows("PURCHASE_RETURN_POST")).hasSize(2);
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
        java.sql.Date.valueOf(LocalDate.now().minusDays(1)));
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "RT 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/masterdata/plants"), Map.of("companyId", companyId, "code", code, "name", "RT 工厂 " + code)),
            token));
    return idOf("plant", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of("plantId", plantId, "code", code, "name", "RT 仓库 " + code, "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", code);
  }

  private long createItem(String token, String code) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "RT 物料 " + code);
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("valuationMethod", "AVCO");
    performOk(authed(json(post("/api/masterdata/items"), body), token));
    return idOf("item", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/suppliers"), Map.of("code", code, "name", "RT 供应商 " + code, "category", "电子")),
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
                    "name", "RT 用例账号",
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
