package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/** E1-W1b：供应商扩展/客户/价格表/税码/科目/映射（docs/erp/52）。 */
@Transactional
class BusinessMasterDataApiIntegrationTest extends AbstractIntegrationTest {

  private long createCompany(String token, String code) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/companies"),
                    Map.of(
                        "code", code,
                        "name", "W1B 公司 " + code,
                        "baseCurrency", "CNY",
                        "country", "CN")),
                token))
        .andExpect(status().isOk());
    return idOf("company", code);
  }

  private long createItem(String token, String code) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "W1B 物料 " + code);
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("valuationMethod", "AVCO");
    mockMvc
        .perform(authed(json(post("/api/masterdata/items"), body), token))
        .andExpect(status().isOk());
    return idOf("item", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/suppliers"),
                    Map.of("code", code, "name", "W1B 供应商 " + code, "category", "电子")),
                token))
        .andExpect(status().isOk());
    return idOf("supplier", code);
  }

  private long idOf(String table, String code) {
    Long id = jdbcTemplate.queryForObject("SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException(table + " 不存在：" + code);
    }
    return id;
  }

  @Test
  @DisplayName("供应商扩展：upsert、脱敏、审计不记税号/银行账号明文")
  void supplierExtensionUpsert() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long supplierId = createSupplier(token, "W1B-SUP-1");

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/masterdata/suppliers/" + supplierId + "/extension"),
                    Map.of(
                        "taxNo", "91310000TEST",
                        "bankAccount", "6222021234567890",
                        "bankName", "测试银行",
                        "paymentTerms", "NET30",
                        "currency", "CNY",
                        "incoterms", "FOB",
                        "qualificationStatus", "APPROVED",
                        "version", 0)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.taxNo").value("****TEST"))
        .andExpect(jsonPath("$.data.bankAccount").value("****7890"))
        .andExpect(jsonPath("$.data.qualificationStatus").value("APPROVED"));

    mockMvc
        .perform(authed(get("/api/masterdata/suppliers/" + supplierId + "/extension"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.paymentTerms").value("NET30"));

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/masterdata/suppliers/" + supplierId + "/extension"),
                    Map.of(
                        "taxNo", "91310000TEST",
                        "bankAccount", "6222021234567890",
                        "bankName", "测试银行",
                        "paymentTerms", "NET45",
                        "currency", "CNY",
                        "incoterms", "FOB",
                        "qualificationStatus", "APPROVED",
                        "version", 0)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(1));

    assertThat(auditRows("SUPPLIER_EXTENSION_UPDATE")).hasSize(2);
    String detail =
        jdbcTemplate
            .queryForObject(
                "SELECT detail FROM audit_log WHERE action = 'SUPPLIER_EXTENSION_UPDATE'"
                    + " ORDER BY id DESC LIMIT 1",
                String.class);
    assertThat(detail).doesNotContain("91310000TEST").doesNotContain("6222021234567890");
  }

  @Test
  @DisplayName("客户：创建/列表/重复/更新/状态/审计")
  void customerCrud() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/customers"),
                    Map.of(
                        "code", "W1B-CUS-1",
                        "name", "测试客户",
                        "taxNo", "91310000CUST",
                        "currency", "CNY",
                        "creditLimit", 100000)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.code").value("W1B-CUS-1"))
        .andExpect(jsonPath("$.data.taxNo").value("****CUST"))
        .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    long customerId = idOf("customer", "W1B-CUS-1");

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/customers"),
                    Map.of("code", "W1B-CUS-1", "name", "重复", "currency", "CNY")),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2114));

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/masterdata/customers/" + customerId),
                    Map.of(
                        "code", "W1B-CUS-1",
                        "name", "测试客户改名",
                        "taxNo", "****CUST",
                        "currency", "CNY",
                        "creditLimit", 120000,
                        "version", 0)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("测试客户改名"))
        .andExpect(jsonPath("$.data.version").value(1));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT tax_no FROM customer WHERE id = ?", String.class, customerId))
        .isEqualTo("91310000CUST");

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/masterdata/customers/" + customerId + "/status"),
                    Map.of("status", "INACTIVE", "version", 1)),
                token))
        .andExpect(status().isOk());
    mockMvc
        .perform(authed(get("/api/masterdata/customers").param("keyword", "W1B-CUS"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].status").value("INACTIVE"));
    assertThat(auditRows("CUSTOMER_CREATE")).hasSize(1);
    assertThat(auditRows("CUSTOMER_UPDATE")).hasSize(1);
    assertThat(auditRows("CUSTOMER_STATUS_CHANGE")).hasSize(1);
  }

  @Test
  @DisplayName("价格表：行新增/重复/列表/删除；无效物料拒绝")
  void priceListAndLines() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long itemId = createItem(token, "W1B-IT-PRICE-1");
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/price-lists"),
                    Map.of(
                        "code", "W1B-PL-1",
                        "listType", "PURCHASE",
                        "currency", "CNY",
                        "validFrom", "2026-01-01")),
                token))
        .andExpect(status().isOk());
    long priceListId = idOf("price_list", "W1B-PL-1");

    Map<String, Object> line = new LinkedHashMap<>();
    line.put("itemId", itemId);
    line.put("uom", "EA");
    line.put("minQty", 0);
    line.put("price", 12.5);
    line.put("taxIncluded", false);
    line.put("validFrom", "2026-01-01");
    mockMvc
        .perform(
            authed(json(post("/api/masterdata/price-lists/" + priceListId + "/lines"), line), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.price").value(12.5));
    mockMvc
        .perform(
            authed(json(post("/api/masterdata/price-lists/" + priceListId + "/lines"), line), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2117));

    mockMvc
        .perform(authed(get("/api/masterdata/price-lists/" + priceListId + "/lines"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
    Long lineId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM price_line WHERE price_list_id = ?", Long.class, priceListId);
    mockMvc
        .perform(
            authed(
                delete("/api/masterdata/price-lists/" + priceListId + "/lines/" + lineId),
                token))
        .andExpect(status().isOk());

    Map<String, Object> invalid = new LinkedHashMap<>(line);
    invalid.put("itemId", 999999L);
    mockMvc
        .perform(
            authed(json(post("/api/masterdata/price-lists/" + priceListId + "/lines"), invalid), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2107));

    assertThat(auditRows("PRICE_LINE_ADD")).hasSize(1);
    assertThat(auditRows("PRICE_LINE_REMOVE")).hasSize(1);
  }

  @Test
  @DisplayName("税码/科目/映射：创建、重复、无效科目、映射唯一键与审计")
  void financeMasterData() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(token, "W1B-CO-FIN-1");

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/tax-codes"),
                    Map.of(
                        "code", "W1B-TAX-13",
                        "country", "CN",
                        "rate", 0.13,
                        "taxType", "INPUT",
                        "effectiveFrom", "2026-01-01")),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rate").value(0.13));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/tax-codes"),
                    Map.of(
                        "code", "W1B-TAX-13",
                        "country", "CN",
                        "rate", 0.13,
                        "taxType", "INPUT",
                        "effectiveFrom", "2026-01-01")),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2120));

    long debitId = createGlAccount(token, companyId, "W1B-GL-1001", "库存");
    long creditId = createGlAccount(token, companyId, "W1B-GL-2001", "应付");
    Map<String, Object> mapping = new LinkedHashMap<>();
    mapping.put("companyId", companyId);
    mapping.put("eventType", "GR");
    mapping.put("dimension", "RAW");
    mapping.put("debitAccountId", debitId);
    mapping.put("creditAccountId", creditId);
    mapping.put("validFrom", "2026-01-01");
    mockMvc
        .perform(authed(json(post("/api/masterdata/account-mappings"), mapping), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.eventType").value("GR"));
    mockMvc
        .perform(authed(json(post("/api/masterdata/account-mappings"), mapping), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2125));

    Map<String, Object> invalid = new LinkedHashMap<>(mapping);
    invalid.put("debitAccountId", 999999L);
    mockMvc
        .perform(authed(json(post("/api/masterdata/account-mappings"), invalid), token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2124));

    assertThat(auditRows("TAX_CODE_CREATE")).hasSize(1);
    assertThat(auditRows("GL_ACCOUNT_CREATE")).hasSize(2);
    assertThat(auditRows("ACCOUNT_MAPPING_CREATE")).hasSize(1);
  }

  private long createGlAccount(String token, long companyId, String code, String name)
      throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/gl-accounts"),
                    Map.of(
                        "companyId", companyId,
                        "code", code,
                        "name", name,
                        "accountType", "ASSET",
                        "normalBalance", "DEBIT")),
                token))
        .andExpect(status().isOk());
    return idOf("gl_account", code);
  }
}
