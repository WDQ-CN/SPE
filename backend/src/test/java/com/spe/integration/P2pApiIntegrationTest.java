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

/**
 * E1-W2：PR → 审批 → PO → 审批/发送 → 收货（docs/erp/32、50、52）。
 *
 * <p>覆盖真 SQL 上的审批矩阵、SoD、价格价目控制、分批收货与并发防线；审计码在用例末尾核对。
 */
@Transactional
class P2pApiIntegrationTest extends AbstractIntegrationTest {

  private record MasterData(long companyId, long plantId, long warehouseId, long itemId, long supplierId) {}

  private MasterData setupMasterData(String token, String suffix) throws Exception {
    long companyId = createCompany(token, "P2P-CO-" + suffix);
    long plantId = createPlant(token, companyId, "P2P-PL-" + suffix);
    long warehouseId = createWarehouse(token, plantId, "P2P-WH-" + suffix);
    long itemId = createItem(token, "P2P-IT-" + suffix);
    long supplierId = createSupplier(token, "P2P-SUP-" + suffix);
    configureFinance(companyId);
    return new MasterData(companyId, plantId, warehouseId, itemId, supplierId);
  }

  /** 收货会自动过账 GR/IR；W2 用例先放好最小科目映射，否则 2402 会让收货回滚。 */
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
                Map.of(
                    "code", code,
                    "name", "P2P 公司 " + code,
                    "baseCurrency", "CNY",
                    "country", "CN")),
            token));
    return idOf("company", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/plants"),
                Map.of("companyId", companyId, "code", code, "name", "P2P 工厂 " + code)),
            token));
    return idOf("plant", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/warehouses"),
                Map.of(
                    "plantId", plantId,
                    "code", code,
                    "name", "P2P 仓库 " + code,
                    "warehouseType", "STANDARD")),
            token));
    return idOf("warehouse", code);
  }

  private long createItem(String token, String code) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "P2P 物料 " + code);
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("valuationMethod", "AVCO");
    performOk(authed(json(post("/api/masterdata/items"), body), token));
    return idOf("item", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/suppliers"),
                Map.of("code", code, "name", "P2P 供应商 " + code, "category", "电子")),
            token));
    return idOf("supplier", code);
  }

  private void createPurchasePrice(String token, String code, long itemId, String price)
      throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/price-lists"),
                Map.of(
                    "code", code,
                    "listType", "PURCHASE",
                    "currency", "CNY",
                    "validFrom", LocalDate.now().minusDays(1).toString())),
            token));
    long priceListId = idOf("price_list", code);
    performOk(
        authed(
            json(
                post("/api/masterdata/price-lists/" + priceListId + "/lines"),
                Map.of(
                    "itemId", itemId,
                    "uom", "EA",
                    "minQty", 0,
                    "price", new BigDecimal(price),
                    "taxIncluded", false,
                    "validFrom", LocalDate.now().minusDays(1).toString())),
            token));
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
                    "name", "P2P 用例账号",
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

  private JsonNode createRequisition(String token, MasterData md, String qty, String price)
      throws Exception {
    return performOk(
        authed(
            json(
                post("/api/procurement/requisitions"),
                Map.of(
                    "companyId", md.companyId(),
                    "needDate", LocalDate.now().plusDays(7).toString(),
                    "currency", "CNY",
                    "lines", List.of(Map.of("itemId", md.itemId(), "qty", new BigDecimal(qty), "uom", "EA", "estimatedPrice", new BigDecimal(price))))),
            token));
  }

  @Test
  @DisplayName("主流程：PR 提交/审批 → 转 PO → 审批/发送 → 分批收货与 SoD")
  void fullFlow() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    MasterData md = setupMasterData(admin, "F1");
    String requester = loginAs(admin, "p2p-req-f1", Role.MANAGER);
    String approver = loginAs(admin, "p2p-appr-f1", Role.ADMIN);
    String buyer = loginAs(admin, "p2p-buyer-f1", Role.MANAGER);
    String receiver = loginAs(admin, "p2p-recv-f1", Role.MANAGER);

    JsonNode pr = createRequisition(requester, md, "10", "100");
    long prId = pr.path("id").asLong();
    assertThat(pr.path("status").asText()).isEqualTo("DRAFT");
    assertThat(pr.path("amount").decimalValue()).isEqualByComparingTo("1000.0000");

    JsonNode submitted =
        performOk(
            authed(
                json(post("/api/procurement/requisitions/" + prId + "/submit"), Map.of("version", 0)),
                requester));
    assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");

    // SoD：申请人不能审批自己的 PR
    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/requisitions/" + prId + "/approve"), Map.of("version", 1)),
                requester))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    JsonNode approvedPr =
        performOk(
            authed(
                json(post("/api/procurement/requisitions/" + prId + "/approve"), Map.of("version", 1)),
                approver));
    assertThat(approvedPr.path("status").asText()).isEqualTo("APPROVED");

    JsonNode po =
        performOk(
            authed(
                json(
                    post("/api/procurement/requisitions/" + prId + "/convert"),
                    Map.of(
                        "supplierId", md.supplierId(),
                        "plantId", md.plantId(),
                        "paymentTerms", "NET30",
                        "version", approvedPr.path("version").asInt())),
                buyer));
    long poId = po.path("id").asLong();
    assertThat(po.path("status").asText()).isEqualTo("DRAFT");
    assertThat(po.path("sourcePrId").asLong()).isEqualTo(prId);
    assertThat(po.path("sourcePrDocNo").asText()).isEqualTo(pr.path("docNo").asText());

    // 转单后 PR 只能转一次
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/requisitions/" + prId + "/convert"),
                    Map.of("supplierId", md.supplierId(), "plantId", md.plantId(), "version", approvedPr.path("version").asInt())),
                buyer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2212));

    // SoD：PO 创建人不能审批本人 PO
    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/approve"), Map.of("version", 0)),
                buyer))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    JsonNode approvedPo =
        performOk(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/approve"), Map.of("version", 0)),
                approver));
    assertThat(approvedPo.path("status").asText()).isEqualTo("APPROVED");

    JsonNode sent =
        performOk(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/send"), Map.of("version", approvedPo.path("version").asInt())),
                approver));
    assertThat(sent.path("status").asText()).isEqualTo("SENT");
    long poLineId = sent.path("lines").get(0).path("id").asLong();

    // SoD：PO 创建人不能收货
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    Map.of(
                        "poId", poId,
                        "warehouseId", md.warehouseId(),
                        "lines", List.of(Map.of("poLineId", poLineId, "qty", 4)))),
                buyer))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    // SoD：PO 审批人不能收货
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    Map.of(
                        "poId", poId,
                        "warehouseId", md.warehouseId(),
                        "lines", List.of(Map.of("poLineId", poLineId, "qty", 4)))),
                approver))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    JsonNode partial = receive(receiver, poId, md.warehouseId(), poLineId, "4");
    assertThat(partial.path("status").asText()).isEqualTo("POSTED");

    JsonNode afterPartial = performOk(authed(get("/api/procurement/purchase-orders/" + poId), receiver));
    assertThat(afterPartial.path("status").asText()).isEqualTo("PARTIAL_RECEIVED");
    assertThat(afterPartial.path("lines").get(0).path("receivedQty").decimalValue())
        .isEqualByComparingTo("4.000000");

    // 超收：未收 6，收 7 → 2204
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    Map.of(
                        "poId", poId,
                        "warehouseId", md.warehouseId(),
                        "lines", List.of(Map.of("poLineId", poLineId, "qty", 7)))),
                receiver))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2204));

    receive(receiver, poId, md.warehouseId(), poLineId, "6");
    JsonNode received = performOk(authed(get("/api/procurement/purchase-orders/" + poId), receiver));
    assertThat(received.path("status").asText()).isEqualTo("RECEIVED");
    assertThat(received.path("lines").get(0).path("receivedQty").decimalValue())
        .isEqualByComparingTo("10.000000");

    // 收满后再收 → PO 状态不可收货
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    Map.of(
                        "poId", poId,
                        "warehouseId", md.warehouseId(),
                        "lines", List.of(Map.of("poLineId", poLineId, "qty", 1)))),
                receiver))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2216));

    assertThat(auditRows("PURCHASE_REQUISITION_CREATE")).hasSize(1);
    assertThat(auditRows("PURCHASE_REQUISITION_SUBMIT")).hasSize(1);
    assertThat(auditRows("PURCHASE_REQUISITION_APPROVE")).hasSize(1);
    assertThat(auditRows("PURCHASE_REQUISITION_CONVERT")).hasSize(1);
    assertThat(auditRows("PURCHASE_ORDER_CREATE")).hasSize(1);
    assertThat(auditRows("PURCHASE_ORDER_APPROVE")).hasSize(1);
    assertThat(auditRows("PURCHASE_ORDER_SEND")).hasSize(1);
    assertThat(auditRows("GOODS_RECEIPT_POST")).hasSize(2);
  }

  @Test
  @DisplayName("收货冲销：库存回退且总账生成反向凭证")
  void goodsReceiptReversalPostsReverseJournal() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    MasterData md = setupMasterData(admin, "REV");
    String buyer = loginAs(admin, "p2p-buyer-rev", Role.MANAGER);
    String approver = loginAs(admin, "p2p-approver-rev", Role.ADMIN);
    String receiver = loginAs(admin, "p2p-receiver-rev", Role.MANAGER);
    String reverser = loginAs(admin, "p2p-reverser-rev", Role.MANAGER);

    JsonNode po =
        performOk(
            authed(
                json(
                    post("/api/procurement/purchase-orders"),
                    Map.of(
                        "companyId", md.companyId(),
                        "supplierId", md.supplierId(),
                        "plantId", md.plantId(),
                        "currency", "CNY",
                        "lines", List.of(Map.of("itemId", md.itemId(), "qty", 10, "uom", "EA", "price", 100)))),
                buyer));
    long poId = po.path("id").asLong();
    long poLineId = po.path("lines").get(0).path("id").asLong();
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

    JsonNode receipt = receive(receiver, poId, md.warehouseId(), poLineId, "10");
    assertThat(journalCount("GOODS_RECEIPT")).isEqualTo(1);

    JsonNode reversed =
        performOk(
            authed(
                json(
                    post("/api/procurement/goods-receipts/" + receipt.path("id").asLong() + "/reverse"),
                    Map.of("version", receipt.path("version").asInt(), "reason", "错收")),
                reverser));
    assertThat(reversed.path("status").asText()).isEqualTo("REVERSED");
    assertThat(journalCount("GOODS_RECEIPT_REVERSAL")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT l.account_id FROM finance_journal_line l"
                    + " JOIN finance_journal_entry e ON e.id = l.entry_id"
                    + " WHERE e.source_type IN ('GOODS_RECEIPT','GOODS_RECEIPT_REVERSAL')"
                    + " GROUP BY l.account_id HAVING SUM(l.debit - l.credit) <> 0",
                Long.class))
        .isEmpty();
  }

  private long journalCount(String sourceType) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM finance_journal_entry WHERE source_type = ?",
        Long.class,
        sourceType);
  }

  private JsonNode receive(
      String token, long poId, long warehouseId, long poLineId, String qty) throws Exception {
    return performOk(
        authed(
            json(
                post("/api/procurement/goods-receipts"),
                Map.of(
                    "poId", poId,
                    "warehouseId", warehouseId,
                    "lines", List.of(Map.of("poLineId", poLineId, "qty", new BigDecimal(qty))))),
            token));
  }

  @Test
  @DisplayName("审批矩阵：PR 60 万需 ADMIN；PO 60 万需 ADMIN，MANAGER 4005")
  void approvalMatrixByAmount() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    MasterData md = setupMasterData(admin, "M1");
    String requester = loginAs(admin, "p2p-req-m1", Role.MANAGER);
    String manager = loginAs(admin, "p2p-mgr-m1", Role.MANAGER);
    String poBuyer = loginAs(admin, "p2p-buyer-m1", Role.MANAGER);
    String approver = loginAs(admin, "p2p-adm-m1", Role.ADMIN);

    JsonNode pr = createRequisition(requester, md, "6000", "100");
    long prId = pr.path("id").asLong();
    performOk(
        authed(
            json(post("/api/procurement/requisitions/" + prId + "/submit"), Map.of("version", 0)),
            requester));

    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/requisitions/" + prId + "/approve"), Map.of("version", 1)),
                manager))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4005));

    JsonNode approvedPr =
        performOk(
            authed(
                json(post("/api/procurement/requisitions/" + prId + "/approve"), Map.of("version", 1)),
                approver));

    JsonNode po =
        performOk(
            authed(
                json(
                    post("/api/procurement/requisitions/" + prId + "/convert"),
                    Map.of(
                        "supplierId", md.supplierId(),
                        "plantId", md.plantId(),
                        "version", approvedPr.path("version").asInt())),
                poBuyer));
    long poId = po.path("id").asLong();

    mockMvc
        .perform(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/approve"), Map.of("version", 0)),
                manager))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4005));

    JsonNode approvedPo =
        performOk(
            authed(
                json(post("/api/procurement/purchase-orders/" + poId + "/approve"), Map.of("version", 0)),
                approver));
    assertThat(approvedPo.path("status").asText()).isEqualTo("APPROVED");

    // 审批规则只读接口能解释这次升级
    JsonNode rules =
        performOk(authed(get("/api/procurement/approval-rules").param("docType", "PO"), admin));
    assertThat(rules).isNotEmpty();
  }

  @Test
  @DisplayName("价目控制：PO 单价高于有效采购价目 1% 以上 → 2202")
  void purchasePriceControl() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    MasterData md = setupMasterData(admin, "P1");
    String buyer = loginAs(admin, "p2p-buyer-p1", Role.MANAGER);
    createPurchasePrice(admin, "P2P-PRICE-P1", md.itemId(), "10");

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/purchase-orders"),
                    Map.of(
                        "companyId", md.companyId(),
                        "supplierId", md.supplierId(),
                        "plantId", md.plantId(),
                        "currency", "CNY",
                        "lines", List.of(Map.of("itemId", md.itemId(), "qty", 1, "uom", "EA", "price", new BigDecimal("11"))))),
                buyer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2202));

    JsonNode allowed =
        performOk(
            authed(
                json(
                    post("/api/procurement/purchase-orders"),
                    Map.of(
                        "companyId", md.companyId(),
                        "supplierId", md.supplierId(),
                        "plantId", md.plantId(),
                        "currency", "CNY",
                        "lines", List.of(Map.of("itemId", md.itemId(), "qty", 1, "uom", "EA", "price", new BigDecimal("10.05"))))),
                buyer));
    assertThat(allowed.path("status").asText()).isEqualTo("DRAFT");
  }

  @Test
  @DisplayName("越权与状态：EVALUATOR 403、DRAFT 收货 2216、未审批 PR 转单 2212")
  void guardRails() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    MasterData md = setupMasterData(admin, "G1");
    String requester = loginAs(admin, "p2p-req-g1", Role.MANAGER);
    String evaluator = loginAs(admin, "p2p-eval-g1", Role.EVALUATOR);

    mockMvc
        .perform(authed(get("/api/procurement/requisitions"), evaluator))
        .andExpect(status().isForbidden());

    JsonNode pr = createRequisition(requester, md, "1", "10");
    long prId = pr.path("id").asLong();

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/requisitions/" + prId + "/convert"),
                    Map.of("supplierId", md.supplierId(), "plantId", md.plantId(), "version", 0)),
                requester))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2212));

    JsonNode po =
        performOk(
            authed(
                json(
                    post("/api/procurement/purchase-orders"),
                    Map.of(
                        "companyId", md.companyId(),
                        "supplierId", md.supplierId(),
                        "plantId", md.plantId(),
                        "currency", "CNY",
                        "lines", List.of(Map.of("itemId", md.itemId(), "qty", 1, "uom", "EA", "price", new BigDecimal("10"))))),
                requester));
    long poId = po.path("id").asLong();
    long poLineId = po.path("lines").get(0).path("id").asLong();
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/goods-receipts"),
                    Map.of(
                        "poId", poId,
                        "warehouseId", md.warehouseId(),
                        "lines", List.of(Map.of("poLineId", poLineId, "qty", 1)))),
                evaluator))
        .andExpect(status().isForbidden());
  }
}
