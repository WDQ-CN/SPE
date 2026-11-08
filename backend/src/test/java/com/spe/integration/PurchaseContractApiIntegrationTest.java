package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.api.Role;
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

/** E2：合同/框架协议、释放 PO、合同消耗与供应商价格库（docs/erp/55 §3.5）。 */
@Transactional
class PurchaseContractApiIntegrationTest extends AbstractIntegrationTest {

  private record Setup(
      long companyId,
      long plantId,
      long itemId,
      long supplierId,
      long contractLineId,
      long contractId,
      String admin,
      String approver) {}

  @Test
  @DisplayName("合同生效、释放订单、数量上限、终止与价格库")
  void contractLifecycle() throws Exception {
    Setup setup = setup();

    // 创建人不能审批本人合同
    mockMvc
        .perform(
            authed(
                post("/api/procurement/contracts/" + setup.contractId() + "/activate"),
                setup.admin()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));

    JsonNode activated =
        performOk(
            authed(
                post("/api/procurement/contracts/" + setup.contractId() + "/activate"),
                setup.approver()));
    assertThat(activated.path("status").asText()).isEqualTo("ACTIVE");

    JsonNode prices =
        performOk(
            authed(
                get("/api/procurement/supplier-prices")
                    .param("supplierId", String.valueOf(setup.supplierId()))
                    .param("itemId", String.valueOf(setup.itemId())),
                setup.admin()));
    assertThat(prices).hasSize(1);
    assertThat(prices.get(0).path("sourceType").asText()).isEqualTo("CONTRACT");
    assertThat(prices.get(0).path("price").decimalValue()).isEqualByComparingTo("50");

    JsonNode po =
        performOk(
            authed(
                json(
                    post("/api/procurement/contracts/" + setup.contractId() + "/release-order"),
                    Map.of(
                        "plantId", setup.plantId(),
                        "lines", List.of(
                            Map.of("contractLineId", setup.contractLineId(), "qty", 6)))),
                setup.approver()));
    assertThat(po.path("status").asText()).isEqualTo("DRAFT");
    assertThat(po.path("lines").get(0).path("price").decimalValue()).isEqualByComparingTo("50");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT contract_id FROM purchase_order WHERE id = ?",
                Long.class,
                po.path("id").asLong()))
        .isEqualTo(setup.contractId());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM purchase_order_line WHERE po_id = ?"
                    + " AND price_source = 'CONTRACT' AND contract_line_id IS NOT NULL",
                Long.class,
                po.path("id").asLong()))
        .isEqualTo(1L);
    JsonNode consumption =
        performOk(
            authed(
                get("/api/procurement/contracts/" + setup.contractId() + "/consumption"),
                setup.admin()));
    assertThat(consumption).hasSize(1);
    assertThat(consumption.get(0).path("qty").decimalValue()).isEqualByComparingTo("6");

    // 行上限 10：再释放 5 → 2242
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/contracts/" + setup.contractId() + "/release-order"),
                    Map.of(
                        "plantId", setup.plantId(),
                        "lines", List.of(
                            Map.of("contractLineId", setup.contractLineId(), "qty", 5)))),
                setup.approver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2242));

    // 释放 4 到上限 10，再次释放 1 → 2242
    performOk(
        authed(
            json(
                post("/api/procurement/contracts/" + setup.contractId() + "/release-order"),
                Map.of(
                    "plantId", setup.plantId(),
                    "lines", List.of(
                        Map.of("contractLineId", setup.contractLineId(), "qty", 4)))),
            setup.approver()));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/procurement/contracts/" + setup.contractId() + "/release-order"),
                    Map.of(
                        "plantId", setup.plantId(),
                        "lines", List.of(
                            Map.of("contractLineId", setup.contractLineId(), "qty", 1)))),
                setup.approver()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2242));

    JsonNode terminated =
        performOk(
            authed(
                post("/api/procurement/contracts/" + setup.contractId() + "/terminate"),
                setup.approver()));
    assertThat(terminated.path("status").asText()).isEqualTo("TERMINATED");

    // 终止后合同价失效，手工价仍可查
    performOk(
        authed(
            json(
                post("/api/procurement/supplier-prices"),
                Map.of(
                    "companyId", setup.companyId(),
                    "supplierId", setup.supplierId(),
                    "itemId", setup.itemId(),
                    "uom", "EA",
                    "minQty", 0,
                    "price", 45,
                    "currency", "CNY",
                    "validFrom", LocalDate.now(ZoneOffset.UTC).toString())),
            setup.admin()));
    JsonNode manualPrices =
        performOk(
            authed(
                get("/api/procurement/supplier-prices")
                    .param("supplierId", String.valueOf(setup.supplierId()))
                    .param("itemId", String.valueOf(setup.itemId())),
                setup.admin()));
    assertThat(manualPrices).hasSize(1);
    assertThat(manualPrices.get(0).path("sourceType").asText()).isEqualTo("MANUAL");
  }

  @Test
  @DisplayName("合同释放的 PO 取消后释放额度，可重新释放")
  void cancelReleasedOrderReleasesContractQuota() throws Exception {
    Setup setup = setup();
    performOk(
        authed(
            post("/api/procurement/contracts/" + setup.contractId() + "/activate"),
            setup.approver()));

    JsonNode po =
        performOk(
            authed(
                json(
                    post("/api/procurement/contracts/" + setup.contractId() + "/release-order"),
                    Map.of(
                        "plantId", setup.plantId(),
                        "lines", List.of(
                            Map.of("contractLineId", setup.contractLineId(), "qty", 6)))),
                setup.approver()));
    JsonNode canceled =
        performOk(
            authed(
                json(
                    post("/api/procurement/purchase-orders/" + po.path("id").asLong() + "/cancel"),
                    Map.of("version", po.path("version").asInt())),
                setup.approver()));
    assertThat(canceled.path("status").asText()).isEqualTo("CANCELED");

    JsonNode consumption =
        performOk(
            authed(
                get("/api/procurement/contracts/" + setup.contractId() + "/consumption"),
                setup.admin()));
    assertThat(consumption).isEmpty();

    JsonNode releasedAgain =
        performOk(
            authed(
                json(
                    post("/api/procurement/contracts/" + setup.contractId() + "/release-order"),
                    Map.of(
                        "plantId", setup.plantId(),
                        "lines", List.of(
                            Map.of("contractLineId", setup.contractLineId(), "qty", 6)))),
                setup.approver()));
    assertThat(releasedAgain.path("status").asText()).isEqualTo("DRAFT");
  }

  private Setup setup() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "CT-CO");
    long plantId = createPlant(admin, companyId, "CT-PL");
    long itemId = createItem(admin, "CT-IT");
    long supplierId = createSupplier(admin, "CT-SUP");
    String approver = loginAs(admin, "ct-approver", Role.ADMIN);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    JsonNode contract =
        performOk(
            authed(
                json(
                    post("/api/procurement/contracts"),
                    Map.of(
                        "companyId", companyId,
                        "supplierId", supplierId,
                        "title", "年度框架",
                        "currency", "CNY",
                        "validFrom", today.minusDays(1).toString(),
                        "validTo", today.plusDays(30).toString(),
                        "totalLimitQty", 15,
                        "lines", List.of(Map.of(
                            "itemId", itemId,
                            "uom", "EA",
                            "price", 50,
                            "maxQty", 10,
                            "taxIncluded", false)))),
                admin));
    return new Setup(
        companyId,
        plantId,
        itemId,
        supplierId,
        contract.path("lines").get(0).path("id").asLong(),
        contract.path("id").asLong(),
        admin,
        approver);
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "CT 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", "code", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/plants"),
                Map.of("companyId", companyId, "code", code, "name", "CT 工厂 " + code)),
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
                    "name", "CT 物料 " + code,
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
                Map.of("code", code, "name", "CT 供应商 " + code, "category", "电子")),
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
                    "name", "CT 用例账号",
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
