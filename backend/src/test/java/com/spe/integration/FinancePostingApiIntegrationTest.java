package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.finance.internal.JournalPostingService;
import com.spe.context.procurement.api.events.GoodsReceivedEvent;
import com.spe.context.system.api.Role;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E1-W5：GR/发票/付款自动过账、试算平衡与 AP 子账对账（docs/erp/34、52）。 */
@Transactional
class FinancePostingApiIntegrationTest extends AbstractIntegrationTest {

  @Autowired private JournalPostingService journalPostingService;

  @Test
  @DisplayName("GR/发票/付款自动过账：借贷平衡、来源幂等、AP 子账=GL")
  void postingLifecycleAndReconciliation() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "W5-CO-1");
    long plantId = createPlant(admin, companyId, "W5-PL-1");
    long warehouseId = createWarehouse(admin, plantId, "W5-WH-1");
    long itemId = createItem(admin, "W5-IT-1");
    long supplierId = createSupplier(admin, "W5-SUP-1");
    createAccountsAndMappings(admin, companyId);

    String buyer = loginAs(admin, "w5-buyer", Role.MANAGER);
    String poApprover = loginAs(admin, "w5-po-approver", Role.ADMIN);
    String receiver = loginAs(admin, "w5-receiver", Role.MANAGER);
    String ap = loginAs(admin, "w5-ap", Role.MANAGER);
    String invoiceApprover = loginAs(admin, "w5-inv-approver", Role.ADMIN);
    String financeManager = loginAs(admin, "w5-fin-mgr", Role.MANAGER);
    String payer = loginAs(admin, "w5-payer", Role.MANAGER);

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
    JsonNode approvedPo =
        performOk(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/approve"), Map.of("version", 0)),
                poApprover));
    performOk(
        authed(
            json(post("/api/procurement/purchase-orders/" + poId + "/send"), Map.of("version", approvedPo.path("version").asInt())),
            poApprover));
    JsonNode receipt =
        performOk(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    Map.of(
                        "poId", poId,
                        "warehouseId", warehouseId,
                        "lines", List.of(Map.of("poLineId", poLineId, "qty", 10)))),
                receiver));
    long receiptId = receipt.path("id").asLong();

    JsonNode entries = performOk(authed(get("/api/finance/journal-entries").param("companyId", String.valueOf(companyId)), admin));
    assertThat(entries.path("total").asLong()).isEqualTo(1);
    JsonNode grEntry = entries.path("list").get(0);
    assertThat(grEntry.path("sourceType").asText()).isEqualTo("GOODS_RECEIPT");
    assertThat(grEntry.path("totalAmount").decimalValue()).isEqualByComparingTo("1000.0000");

    // 重复事件（同一 GR）不重复入账
    journalPostingService.postGoodsReceipt(
        new GoodsReceivedEvent(
            receiptId,
            poId,
            companyId,
            supplierId,
            warehouseId,
            "CNY",
            new BigDecimal("1000"),
            LocalDate.now(),
            List.of()));
    JsonNode afterDuplicate =
        performOk(authed(get("/api/finance/journal-entries").param("companyId", String.valueOf(companyId)), admin));
    assertThat(afterDuplicate.path("total").asLong()).isEqualTo(1);

    JsonNode invoice =
        performOk(
            authed(
                json(
                    post("/api/procurement/invoices"),
                    Map.of(
                        "poId", poId,
                        "supplierInvoiceNo", "W5-INV-1",
                        "invoiceDate", LocalDate.now().toString(),
                        "currency", "CNY",
                        "taxAmount", new BigDecimal("130"),
                        "lines", List.of(Map.of("poLineId", poLineId, "qty", 10, "unitPrice", 100)))),
                ap));
    performOk(
        authed(
            json(post("/api/procurement/invoices/" + invoice.path("id").asLong() + "/approve"), Map.of("version", invoice.path("version").asInt())),
            invoiceApprover));

    JsonNode payment =
        performOk(
            authed(
                json(
                    post("/api/procurement/payments"),
                    Map.of("invoiceId", invoice.path("id").asLong(), "amount", new BigDecimal("1130"))),
                ap));
    JsonNode approvedPayment =
        performOk(
            authed(
                json(post("/api/procurement/payments/" + payment.path("id").asLong() + "/approve"), Map.of("version", payment.path("version").asInt())),
                financeManager));
    performOk(
        authed(
            json(post("/api/procurement/payments/" + payment.path("id").asLong() + "/pay"), Map.of("version", approvedPayment.path("version").asInt())),
            payer));

    entries = performOk(authed(get("/api/finance/journal-entries").param("companyId", String.valueOf(companyId)), admin));
    assertThat(entries.path("total").asLong()).isEqualTo(3);
    assertThat(entries.path("list").get(0).path("sourceType").asText()).isEqualTo("PAYMENT");
    assertThat(entries.path("list").get(1).path("sourceType").asText()).isEqualTo("SUPPLIER_INVOICE");
    assertThat(entries.path("list").get(2).path("sourceType").asText()).isEqualTo("GOODS_RECEIPT");

    JsonNode trial =
        performOk(
            authed(
                get("/api/finance/reports/trial-balance")
                    .param("companyId", String.valueOf(companyId)),
                admin));
    assertThat(trial.path("balanced").asBoolean()).isTrue();
    assertThat(trial.path("totalDebit").decimalValue()).isEqualByComparingTo("3260.0000");
    assertThat(trial.path("totalCredit").decimalValue()).isEqualByComparingTo("3260.0000");
    assertThat(findRow(trial.path("rows"), "1405").path("balance").decimalValue())
        .isEqualByComparingTo("1000.0000");
    assertThat(findRow(trial.path("rows"), "2150").path("balance").decimalValue())
        .isEqualByComparingTo("0.0000");
    // 发票贷记 AP 1130、付款借记 AP 1130 ⇒ 已付清，AP 余额为 0
    assertThat(findRow(trial.path("rows"), "2202").path("balance").decimalValue())
        .isEqualByComparingTo("0.0000");
    assertThat(findRow(trial.path("rows"), "1002").path("creditTotal").decimalValue())
        .isEqualByComparingTo("1130.0000");
    assertThat(findRow(trial.path("rows"), "2220").path("debitTotal").decimalValue())
        .isEqualByComparingTo("130.0000");

    JsonNode apRec =
        performOk(
            authed(
                get("/api/finance/reports/ap-reconciliation")
                    .param("companyId", String.valueOf(companyId)),
                admin));
    assertThat(apRec.path("subledgerBalance").decimalValue()).isEqualByComparingTo("0.0000");
    assertThat(apRec.path("glBalance").decimalValue()).isEqualByComparingTo("0.0000");
    assertThat(apRec.path("difference").decimalValue()).isEqualByComparingTo("0.0000");
    assertThat(apRec.path("reconciled").asBoolean()).isTrue();

    // asOf 同时截断子账与 GL：截至昨天应没有任何发票/付款/分录
    JsonNode historical =
        performOk(
            authed(
                get("/api/finance/reports/ap-reconciliation")
                    .param("companyId", String.valueOf(companyId))
                    .param("asOf", LocalDate.now().minusDays(7).toString()),
                admin));
    assertThat(historical.path("invoicedTotal").decimalValue()).isEqualByComparingTo("0.0000");
    assertThat(historical.path("paidTotal").decimalValue()).isEqualByComparingTo("0.0000");
    assertThat(historical.path("glBalance").decimalValue()).isEqualByComparingTo("0.0000");
    assertThat(historical.path("reconciled").asBoolean()).isTrue();

    // 分录不可变：没有写/删凭证的端点（只读查询）
    long entryId = entries.path("list").get(0).path("id").asLong();
    JsonNode detail = performOk(authed(get("/api/finance/journal-entries/" + entryId), admin));
    assertThat(detail.path("lines")).isNotEmpty();
    assertThat(detail.path("docNo").asText()).startsWith("JE-");

    assertThat(auditRows("JOURNAL_ENTRY_POST")).hasSize(3);
  }

  @Test
  @DisplayName("财务接口权限：EVALUATOR 403")
  void reportPermissions() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "W5-CO-2");
    String evaluator = loginAs(admin, "w5-eval", Role.EVALUATOR);
    mockMvc
        .perform(
            authed(
                get("/api/finance/reports/trial-balance").param("companyId", String.valueOf(companyId)),
                evaluator))
        .andExpect(status().isForbidden());
  }

  private JsonNode findRow(JsonNode rows, String accountCode) {
    for (JsonNode row : rows) {
      if (accountCode.equals(row.path("accountCode").asText())) {
        return row;
      }
    }
    throw new AssertionError("试算平衡中找不到科目 " + accountCode + "：" + rows);
  }

  private void createAccountsAndMappings(String token, long companyId) throws Exception {
    long inventory = createAccount(token, companyId, "1405", "库存商品", "ASSET", "DEBIT");
    long grIr = createAccount(token, companyId, "2150", "GR/IR 暂估", "LIABILITY", "CREDIT");
    long inputTax = createAccount(token, companyId, "2220", "应交税费-进项", "ASSET", "DEBIT");
    long ap = createAccount(token, companyId, "2202", "应付账款", "LIABILITY", "CREDIT");
    long bank = createAccount(token, companyId, "1002", "银行存款", "ASSET", "DEBIT");
    createMapping(token, companyId, "GOODS_RECEIPT", inventory, grIr, null);
    createMapping(token, companyId, "SUPPLIER_INVOICE", grIr, ap, inputTax);
    createMapping(token, companyId, "PAYMENT", ap, bank, null);
  }

  private long createAccount(
      String token, long companyId, String code, String name, String type, String normalBalance)
      throws Exception {
    JsonNode created =
        performOk(
            authed(
                json(
                    post("/api/masterdata/gl-accounts"),
                    Map.of(
                        "companyId", companyId,
                        "code", code,
                        "name", name,
                        "accountType", type,
                        "normalBalance", normalBalance,
                        "requiresCostCenter", false)),
                token));
    return created.path("id").asLong();
  }

  private void createMapping(
      String token, long companyId, String eventType, long debit, long credit, Long tax)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("companyId", companyId);
    body.put("eventType", eventType);
    body.put("dimension", "");
    body.put("debitAccountId", debit);
    body.put("creditAccountId", credit);
    if (tax != null) {
      body.put("taxAccountId", tax);
    }
    body.put("validFrom", LocalDate.now().minusYears(1).toString());
    performOk(authed(json(post("/api/masterdata/account-mappings"), body), token));
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "W5 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/masterdata/plants"), Map.of("companyId", companyId, "code", code, "name", "W5 工厂 " + code)),
            token));
    return idOf("plant", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of("plantId", plantId, "code", code, "name", "W5 仓库 " + code, "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", code);
  }

  private long createItem(String token, String code) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "W5 物料 " + code);
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("valuationMethod", "AVCO");
    performOk(authed(json(post("/api/masterdata/items"), body), token));
    return idOf("item", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/suppliers"), Map.of("code", code, "name", "W5 供应商 " + code, "category", "电子")),
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
                    "name", "W5 用例账号",
                    "role", role.name(),
                    "department", "FINANCE")),
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
