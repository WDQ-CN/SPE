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

/** E2 寻源：RFQ/门户报价/密封/比价/定标/转 PO（docs/erp/55）。 */
@Transactional
class SourcingRfqApiIntegrationTest extends AbstractIntegrationTest {

  private record Setup(
      long companyId,
      long plantId,
      long itemId,
      long supplier1,
      long supplier2,
      String portal1,
      String portal2,
      String admin) {}

  @Test
  @DisplayName("密封 RFQ、门户报价、比价、定标与转 PO")
  void sourcingLifecycle() throws Exception {
    Setup setup = setup();
    JsonNode rfq = createRfq(setup, true, "WHOLE");
    long rfqId = rfq.path("id").asLong();
    long line1 = rfq.path("lines").get(0).path("id").asLong();
    long line2 = rfq.path("lines").get(1).path("id").asLong();

    performOk(
        authed(
            json(
                post("/api/sourcing/rfqs/" + rfqId + "/invite"),
                Map.of("supplierIds", List.of(setup.supplier1(), setup.supplier2()))),
            setup.admin()));
    performOk(authed(post("/api/sourcing/rfqs/" + rfqId + "/send"), setup.admin()));

    // 密封报价截止前内部不可见
    mockMvc
        .perform(authed(get("/api/sourcing/rfqs/" + rfqId + "/quotes"), setup.admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2236));

    JsonNode portalList =
        performOk(authed(get("/api/supplier-portal/rfqs"), setup.portal1()));
    assertThat(portalList.path("total").asLong()).isEqualTo(1);

    JsonNode quote1 =
        submitQuote(setup.portal1(), rfqId, line1, line2, "100", "200", 0.13);
    JsonNode quote2 =
        submitQuote(setup.portal2(), rfqId, line1, line2, "90", "210", 0.13);
    assertThat(quote1.path("supplierId").asLong()).isEqualTo(setup.supplier1());
    assertThat(quote2.path("supplierId").asLong()).isEqualTo(setup.supplier2());

    // 截止前重报生效
    JsonNode quote1Again =
        submitQuote(setup.portal1(), rfqId, line1, line2, "95", "200", 0.13);
    assertThat(quote1Again.path("version").asInt()).isEqualTo(1);
    assertThat(quote1Again.path("lines").get(0).path("unitPrice").decimalValue())
        .isEqualByComparingTo("95");

    performOk(authed(post("/api/sourcing/rfqs/" + rfqId + "/close"), setup.admin()));
    JsonNode quotes =
        performOk(authed(get("/api/sourcing/rfqs/" + rfqId + "/quotes"), setup.admin()));
    assertThat(quotes).hasSize(2);

    JsonNode comparison =
        performOk(
            authed(get("/api/sourcing/rfqs/" + rfqId + "/comparison"), setup.admin()));
    JsonNode lineOne = comparison.path("lines").get(0);
    assertThat(lineOne.path("bestSupplierId").asLong()).isEqualTo(setup.supplier2());
    JsonNode lineTwo = comparison.path("lines").get(1);
    assertThat(lineTwo.path("bestSupplierId").asLong()).isEqualTo(setup.supplier1());

    long quote1Line1 = quote1Again.path("lines").get(0).path("id").asLong();
    long quote1Line2 = quote1Again.path("lines").get(1).path("id").asLong();

    // 超出可供量 → 2237
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/sourcing/rfqs/" + rfqId + "/awards"),
                    Map.of(
                        "supplierId", setup.supplier1(),
                        "lines", List.of(Map.of(
                            "rfqLineId", line1,
                            "quoteLineId", quote1Line1,
                            "awardedQty", 11)))),
                setup.admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2237));

    JsonNode award =
        performOk(
            authed(
                json(
                    post("/api/sourcing/rfqs/" + rfqId + "/awards"),
                    Map.of(
                        "supplierId", setup.supplier1(),
                        "reason", "综合交期与价格",
                        "lines", List.of(
                            Map.of(
                                "rfqLineId", line1,
                                "quoteLineId", quote1Line1,
                                "awardedQty", 10),
                            Map.of(
                                "rfqLineId", line2,
                                "quoteLineId", quote1Line2,
                                "awardedQty", 10)))),
                setup.admin()));
    long awardId = award.path("id").asLong();
    assertThat(award.path("status").asText()).isEqualTo("DRAFT");

    // 创建人不能审批本人定标
    mockMvc
        .perform(authed(post("/api/sourcing/awards/" + awardId + "/approve"), setup.admin()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    String approver = loginAs(setup.admin(), "src-approver", Role.ADMIN);
    JsonNode approved =
        performOk(authed(post("/api/sourcing/awards/" + awardId + "/approve"), approver));
    assertThat(approved.path("status").asText()).isEqualTo("APPROVED");

    JsonNode po =
        performOk(authed(post("/api/sourcing/awards/" + awardId + "/to-po"), approver));
    assertThat(po.path("status").asText()).isEqualTo("DRAFT");
    assertThat(po.path("supplierId").asLong()).isEqualTo(setup.supplier1());
    assertThat(po.path("lines").get(0).path("price").decimalValue()).isEqualByComparingTo("95");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT source_award_id FROM purchase_order WHERE id = ?", Long.class, po.path("id").asLong()))
        .isEqualTo(awardId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_order_line WHERE po_id = ? AND price_source = 'AWARD'"
                    + " AND award_line_id IS NOT NULL",
                Long.class,
                po.path("id").asLong()))
        .isEqualTo(2L);
    mockMvc
        .perform(authed(post("/api/sourcing/awards/" + awardId + "/to-po"), approver))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2240));

    // 未邀请供应商的门户不可见第二张 RFQ
    JsonNode second = createRfq(setup, false, "LINE");
    performOk(
        authed(
            json(
                post("/api/sourcing/rfqs/" + second.path("id").asLong() + "/invite"),
                Map.of("supplierIds", List.of(setup.supplier2()))),
            setup.admin()));
    performOk(
        authed(post("/api/sourcing/rfqs/" + second.path("id").asLong() + "/send"), setup.admin()));
    mockMvc
        .perform(
            authed(
                get("/api/supplier-portal/rfqs/" + second.path("id").asLong()),
                setup.portal1()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2241));
  }

  private JsonNode createRfq(Setup setup, boolean sealed, String awardPolicy) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("companyId", setup.companyId());
    body.put("plantId", setup.plantId());
    body.put("title", sealed ? "密封 RFQ" : "公开 RFQ");
    body.put("currency", "CNY");
    body.put("deadlineAt", LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10).toString());
    body.put("sealed", sealed);
    body.put("awardPolicy", awardPolicy);
    body.put("lines", List.of(
        Map.of("itemId", setup.itemId(), "qty", 10, "uom", "EA", "targetPrice", 110),
        Map.of("itemId", setup.itemId(), "qty", 10, "uom", "BOX", "targetPrice", 220)));
    return performOk(authed(json(post("/api/sourcing/rfqs"), body), setup.admin()));
  }

  private JsonNode submitQuote(
      String portalToken,
      long rfqId,
      long line1,
      long line2,
      String price1,
      String price2,
      double taxRate) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("validUntil", LocalDate.now(ZoneOffset.UTC).plusDays(30).toString());
    body.put("lines", List.of(
        Map.of(
            "rfqLineId", line1,
            "unitPrice", new BigDecimal(price1),
            "taxRate", taxRate,
            "leadTimeDays", 5,
            "qtyAvailable", 20,
            "incoterm", "DDP"),
        Map.of(
            "rfqLineId", line2,
            "unitPrice", new BigDecimal(price2),
            "taxRate", taxRate,
            "leadTimeDays", 7,
            "qtyAvailable", 20,
            "incoterm", "DDP")));
    return performOk(
        authed(
            json(post("/api/supplier-portal/rfqs/" + rfqId + "/quotes"), body), portalToken));
  }

  private Setup setup() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "SR-CO");
    long plantId = createPlant(admin, companyId, "SR-PL");
    long itemId = createItem(admin, "SR-IT");
    long supplier1 = createSupplier(admin, "SR-SUP-1");
    long supplier2 = createSupplier(admin, "SR-SUP-2");
    createPortalUser(admin, supplier1, "sr-portal-1");
    createPortalUser(admin, supplier2, "sr-portal-2");
    String portal1 = portalLogin("sr-portal-1");
    String portal2 = portalLogin("sr-portal-2");
    return new Setup(companyId, plantId, itemId, supplier1, supplier2, portal1, portal2, admin);
  }

  private void createPortalUser(String adminToken, long supplierId, String loginName)
      throws Exception {
    performOk(
        authed(
            json(
                post("/api/suppliers/" + supplierId + "/portal-user"),
                Map.of("loginName", loginName, "password", DEFAULT_PASSWORD)),
            adminToken));
  }

  private String portalLogin(String loginName) throws Exception {
    return performOk(
            json(
                post("/api/supplier-portal/auth/login"),
                Map.of("loginName", loginName, "password", DEFAULT_PASSWORD)))
        .path("accessToken")
        .asText();
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "SR 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", "code", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/plants"),
                Map.of("companyId", companyId, "code", code, "name", "SR 工厂 " + code)),
            token));
    return idOf("plant", "code", code);
  }

  private long createItem(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/items"),
                Map.of(
                    "code", code,
                    "name", "SR 物料 " + code,
                    "itemType", "RAW",
                    "baseUom", "EA",
                    "lotControlled", false,
                    "serialControlled", false,
                    "valuationMethod", "AVCO")),
            token));
    return idOf("item", "code", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/suppliers"),
                Map.of("code", code, "name", "SR 供应商 " + code, "category", "电子")),
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
                    "name", "SR 用例账号",
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
