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

/** E1-W4：发票录入、三单匹配、异常批准与付款（docs/erp/32 §4、50 §3、52）。 */
@Transactional
class P2pInvoicePaymentApiIntegrationTest extends AbstractIntegrationTest {

  private record Setup(
      long companyId, long plantId, long warehouseId, long itemId, long supplierId, long poId, long poLineId) {}

  private Setup setupReceivedOrder(String admin) throws Exception {
    long companyId = createCompany(admin, "W4-CO-1");
    long plantId = createPlant(admin, companyId, "W4-PL-1");
    long warehouseId = createWarehouse(admin, plantId, "W4-WH-1");
    long itemId = createItem(admin, "W4-IT-1");
    long supplierId = createSupplier(admin, "W4-SUP-1");
    configureFinance(companyId);
    String buyer = loginAs(admin, "w4-buyer", Role.MANAGER);
    String approver = loginAs(admin, "w4-po-approver", Role.ADMIN);
    String receiver = loginAs(admin, "w4-receiver", Role.MANAGER);

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
                approver));
    JsonNode sent =
        performOk(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/send"), Map.of("version", approved.path("version").asInt())),
                approver));
    performOk(
        authed(
            json(
                post("/api/procurement/goods-receipts"),
                Map.of(
                    "poId", poId,
                    "warehouseId", warehouseId,
                    "lines", List.of(Map.of("poLineId", poLineId, "qty", 10)))),
            receiver));
    assertThat(sent.path("status").asText()).isEqualTo("SENT");
    return new Setup(companyId, plantId, warehouseId, itemId, supplierId, poId, poLineId);
  }

  private JsonNode createInvoice(
      String token, Setup setup, String invoiceNo, String qty, String unitPrice, String tax) throws Exception {
    return performOk(
        authed(
            json(
                post("/api/procurement/invoices"),
                Map.of(
                    "poId", setup.poId(),
                    "supplierInvoiceNo", invoiceNo,
                    "invoiceDate", LocalDate.now().toString(),
                    "dueDate", LocalDate.now().plusDays(30).toString(),
                    "currency", "CNY",
                    "taxAmount", new BigDecimal(tax),
                    "lines", List.of(Map.of("poLineId", setup.poLineId(), "qty", new BigDecimal(qty), "unitPrice", new BigDecimal(unitPrice))))),
            token));
  }

  @Test
  @DisplayName("主流程：匹配通过 → 批准 → 付款全付；重复发票号与 SoD 被阻断")
  void invoiceMatchApproveAndPay() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Setup setup = setupReceivedOrder(admin);
    String ap = loginAs(admin, "w4-ap", Role.MANAGER);
    String invoiceApprover = loginAs(admin, "w4-inv-approver", Role.ADMIN);
    String financeManager = loginAs(admin, "w4-fin-mgr", Role.MANAGER);
    String payer = loginAs(admin, "w4-payer", Role.MANAGER);
    String receiver = login("w4-receiver", DEFAULT_PASSWORD);

    JsonNode invoice = createInvoice(ap, setup, "INV-2026-0001", "10", "100", "130");
    long invoiceId = invoice.path("id").asLong();
    assertThat(invoice.path("status").asText()).isEqualTo("MATCHED");
    assertThat(invoice.path("match").path("status").asText()).isEqualTo("MATCHED");
    assertThat(invoice.path("netAmount").decimalValue()).isEqualByComparingTo("1000.0000");
    assertThat(invoice.path("taxAmount").decimalValue()).isEqualByComparingTo("130.0000");
    assertThat(invoice.path("totalAmount").decimalValue()).isEqualByComparingTo("1130.0000");

    // 同一供应商发票号唯一（P2P-006）
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/invoices"),
                    Map.of(
                        "poId", setup.poId(),
                        "supplierInvoiceNo", "INV-2026-0001",
                        "invoiceDate", LocalDate.now().toString(),
                        "currency", "CNY",
                        "taxAmount", 0,
                        "lines", List.of(Map.of("poLineId", setup.poLineId(), "qty", 1, "unitPrice", 100)))),
                ap))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2205));

    // SoD：录入人不能批准本人发票
    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/invoices/" + invoiceId + "/approve"), Map.of("version", invoice.path("version").asInt())),
                ap))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    // SoD：该 PO 的收货人不能审批发票
    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/invoices/" + invoiceId + "/approve"), Map.of("version", invoice.path("version").asInt())),
                receiver))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    JsonNode approved =
        performOk(
            authed(
                json(
                    post("/api/procurement/invoices/" + invoiceId + "/approve"),
                    Map.of("version", invoice.path("version").asInt(), "comment", "匹配通过")),
                invoiceApprover));
    assertThat(approved.path("status").asText()).isEqualTo("APPROVED");

    JsonNode po = performOk(authed(get("/api/procurement/purchase-orders/" + setup.poId()), admin));
    assertThat(po.path("lines").get(0).path("invoicedQty").decimalValue())
        .isEqualByComparingTo("10.000000");

    JsonNode payment =
        performOk(
            authed(
                json(
                    post("/api/procurement/payments"),
                    Map.of("invoiceId", invoiceId, "amount", new BigDecimal("1130"))),
                ap));
    long paymentId = payment.path("id").asLong();
    assertThat(payment.path("status").asText()).isEqualTo("REQUESTED");

    // 申请人不能审批自己的付款
    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/payments/" + paymentId + "/approve"), Map.of("version", payment.path("version").asInt())),
                ap))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    // SoD：收货人不能审批该 PO 的付款
    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/payments/" + paymentId + "/approve"), Map.of("version", payment.path("version").asInt())),
                receiver))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    JsonNode approvedPayment =
        performOk(
            authed(
                json(post("/api/procurement/payments/" + paymentId + "/approve"), Map.of("version", payment.path("version").asInt())),
                financeManager));
    assertThat(approvedPayment.path("status").asText()).isEqualTo("APPROVED");

    // 审批人不能自己执行付款（审批-支付分离）
    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/payments/" + paymentId + "/pay"), Map.of("version", approvedPayment.path("version").asInt())),
                financeManager))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    JsonNode paid =
        performOk(
            authed(
                json(post("/api/procurement/payments/" + paymentId + "/pay"), Map.of("version", approvedPayment.path("version").asInt())),
                payer));
    assertThat(paid.path("status").asText()).isEqualTo("PAID");

    JsonNode paidInvoice = performOk(authed(get("/api/procurement/invoices/" + invoiceId), admin));
    assertThat(paidInvoice.path("status").asText()).isEqualTo("PAID");
    assertThat(paidInvoice.path("paidAmount").decimalValue()).isEqualByComparingTo("1130.0000");

    // 已付清后不能再建付款
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/payments"),
                    Map.of("invoiceId", invoiceId, "amount", new BigDecimal("1"))),
                ap))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2207));

    assertThat(auditRows("SUPPLIER_INVOICE_CREATE")).hasSize(1);
    assertThat(auditRows("SUPPLIER_INVOICE_APPROVE")).hasSize(1);
    assertThat(auditRows("PAYMENT_REQUEST_CREATE")).hasSize(1);
    assertThat(auditRows("PAYMENT_APPROVE")).hasSize(1);
    assertThat(auditRows("PAYMENT_PAY")).hasSize(1);
  }

  @Test
  @DisplayName("异常匹配：价格超容差落 EXCEPTION；无原因批准 2206，有原因可通过；可重跑匹配")
  void exceptionMatchAndRematch() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Setup setup = setupReceivedOrder(admin);
    String ap = loginAs(admin, "w4-ap-ex", Role.MANAGER);
    String invoiceApprover = loginAs(admin, "w4-inv-appr-ex", Role.MANAGER);

    JsonNode invoice = createInvoice(ap, setup, "INV-2026-EX-1", "1", "105", "0");
    long invoiceId = invoice.path("id").asLong();
    assertThat(invoice.path("status").asText()).isEqualTo("EXCEPTION");
    assertThat(invoice.path("match").path("status").asText()).isEqualTo("EXCEPTION");
    assertThat(invoice.path("match").path("priceVariance").decimalValue())
        .isEqualByComparingTo("5.0000");

    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/invoices/" + invoiceId + "/approve"), Map.of("version", invoice.path("version").asInt())),
                invoiceApprover))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2206));

    JsonNode rematched =
        performOk(
            authed(
                json(post("/api/procurement/invoices/" + invoiceId + "/match"), Map.of("version", invoice.path("version").asInt())),
                invoiceApprover));
    assertThat(rematched.path("status").asText()).isEqualTo("EXCEPTION");

    JsonNode approved =
        performOk(
            authed(
                json(
                    post("/api/procurement/invoices/" + invoiceId + "/approve"),
                    Map.of("version", rematched.path("version").asInt(), "comment", "供应商涨价已获采购确认")),
                invoiceApprover));
    assertThat(approved.path("status").asText()).isEqualTo("APPROVED");

    assertThat(auditRows("SUPPLIER_INVOICE_MATCH")).hasSize(1);
    String detail =
        jdbcTemplate.queryForObject(
            "SELECT detail FROM audit_log WHERE action = 'SUPPLIER_INVOICE_APPROVE' ORDER BY id DESC LIMIT 1",
            String.class);
    assertThat(detail).contains("供应商涨价已获采购确认");
  }

  @Test
  @DisplayName("累计开票守卫：两张匹配发票先后创建后，第二张批准被行级守卫拦为 2228")
  void cumulativeInvoicedGuard() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Setup setup = setupReceivedOrder(admin);
    String ap = loginAs(admin, "w4-ap-cum", Role.MANAGER);
    String invoiceApprover = loginAs(admin, "w4-inv-appr-cum", Role.ADMIN);

    // 两张发票都在第一张批准前创建：匹配阶段都看到 10 的可开票量。
    JsonNode first = createInvoice(ap, setup, "INV-CUM-1", "6", "100", "0");
    JsonNode second = createInvoice(ap, setup, "INV-CUM-2", "6", "100", "0");
    assertThat(first.path("status").asText()).isEqualTo("MATCHED");
    assertThat(second.path("status").asText()).isEqualTo("MATCHED");

    performOk(
        authed(
            json(
                post("/api/procurement/invoices/" + first.path("id").asLong() + "/approve"),
                Map.of("version", first.path("version").asInt())),
            invoiceApprover));

    // 已收 10 + 数量容差 1 = 11；6 + 6 = 12，超过上限。
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/invoices/" + second.path("id").asLong() + "/approve"),
                    Map.of("version", second.path("version").asInt())),
                invoiceApprover))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2228));
  }

  @Test
  @DisplayName("取消与付款额度：有发票的 SENT 订单不能取消；在途付款占用后第二笔申请 2225")
  void cancelAndOutstandingGuards() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Setup base = setupReceivedOrder(admin);
    String buyer = login("w4-buyer", DEFAULT_PASSWORD);
    String poApprover = login("w4-po-approver", DEFAULT_PASSWORD);
    String ap = loginAs(admin, "w4-ap-guard", Role.MANAGER);
    String invoiceApprover = loginAs(admin, "w4-inv-appr-guard", Role.MANAGER);

    JsonNode po =
        performOk(
            authed(
                json(
                    post("/api/procurement/purchase-orders"),
                    Map.of(
                        "companyId", base.companyId(),
                        "supplierId", base.supplierId(),
                        "plantId", base.plantId(),
                        "currency", "CNY",
                        "lines", List.of(Map.of("itemId", base.itemId(), "qty", 1, "uom", "EA", "price", 100)))),
                buyer));
    long poId = po.path("id").asLong();
    long poLineId = po.path("lines").get(0).path("id").asLong();
    JsonNode approved =
        performOk(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/approve"), Map.of("version", 0)),
                poApprover));
    JsonNode sent =
        performOk(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/send"), Map.of("version", approved.path("version").asInt())),
                poApprover));

    JsonNode invoice =
        createInvoice(
            ap,
            new Setup(base.companyId(), base.plantId(), base.warehouseId(), base.itemId(), base.supplierId(), poId, poLineId),
            "INV-GUARD-1",
            "1",
            "100",
            "0");
    assertThat(invoice.path("status").asText()).isEqualTo("EXCEPTION");

    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/cancel"), Map.of("version", sent.path("version").asInt())),
                buyer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2209));

    JsonNode approvedInvoice =
        performOk(
            authed(
                json(
                    post("/api/procurement/invoices/" + invoice.path("id").asLong() + "/approve"),
                    Map.of("version", invoice.path("version").asInt(), "comment", "无收货例外，采购确认")),
                invoiceApprover));
    assertThat(approvedInvoice.path("status").asText()).isEqualTo("APPROVED");

    JsonNode payment =
        performOk(
            authed(
                json(
                    post("/api/procurement/payments"),
                    Map.of("invoiceId", invoice.path("id").asLong(), "amount", new BigDecimal("100"))),
                ap));
    assertThat(payment.path("status").asText()).isEqualTo("REQUESTED");

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/payments"),
                    Map.of("invoiceId", invoice.path("id").asLong(), "amount", new BigDecimal("1"))),
                ap))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2225));
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "W4 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/masterdata/plants"), Map.of("companyId", companyId, "code", code, "name", "W4 工厂 " + code)),
            token));
    return idOf("plant", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of("plantId", plantId, "code", code, "name", "W4 仓库 " + code, "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", code);
  }

  private long createItem(String token, String code) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "W4 物料 " + code);
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("valuationMethod", "AVCO");
    performOk(authed(json(post("/api/masterdata/items"), body), token));
    return idOf("item", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/suppliers"), Map.of("code", code, "name", "W4 供应商 " + code, "category", "电子")),
            token));
    return idOf("supplier", code);
  }

  /** 收货会自动过账 GR/IR；W4 用例先放好最小科目映射，否则 2402 会让收货回滚。 */
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
                    "name", "W4 用例账号",
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
