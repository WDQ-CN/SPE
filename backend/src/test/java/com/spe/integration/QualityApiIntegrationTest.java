package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E5 质量：检验计划/检验单/NCR/CAPA/证书（docs/erp/37）。 */
@Transactional
class QualityApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("检验不合格自动开 NCR、处置/CAPA 闭环与证书有效期")
  void inspectionNcrCapaAndCertificate() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "QC-CO");
    long itemId = createItem(admin, "QC-IT");
    long supplierId = createSupplier(admin, "QC-SUP");

    JsonNode plan =
        performOk(
            authed(
                json(
                    post("/api/quality/inspection-plans"),
                    Map.of(
                        "companyId", companyId,
                        "itemId", itemId,
                        "supplierId", supplierId,
                        "inspectionType", "IQC",
                        "samplingPlan", "GB2828",
                        "aql", 1.0,
                        "characteristics", List.of(
                            Map.of("name", "外观", "spec", "无划伤", "critical", true),
                            Map.of("name", "尺寸", "spec", "10±0.1", "critical", false)))),
                admin));
    long planId = plan.path("id").asLong();
    assertThat(plan.path("status").asText()).isEqualTo("DRAFT");
    JsonNode activePlan =
        performOk(authed(post("/api/quality/inspection-plans/" + planId + "/activate"), admin));
    assertThat(activePlan.path("status").asText()).isEqualTo("ACTIVE");

    JsonNode inspection =
        performOk(
            authed(
                json(
                    post("/api/quality/inspection-orders"),
                    Map.of(
                        "companyId", companyId,
                        "planId", planId,
                        "sourceType", "GOODS_RECEIPT",
                        "sourceId", 9001,
                        "itemId", itemId,
                        "supplierId", supplierId,
                        "lotNo", "LOT-Q1",
                        "qty", 10)),
                admin));
    long inspectionId = inspection.path("id").asLong();
    assertThat(inspection.path("status").asText()).isEqualTo("PENDING");

    JsonNode failed =
        performOk(
            authed(
                json(
                    post("/api/quality/inspection-orders/" + inspectionId + "/results"),
                    Map.of(
                        "lines", List.of(
                            Map.of(
                                "name", "外观",
                                "measuredValue", "划伤",
                                "result", "FAIL"),
                            Map.of(
                                "name", "尺寸",
                                "measuredValue", "10.02",
                                "result", "PASS")))),
                admin));
    assertThat(failed.path("status").asText()).isEqualTo("FAILED");
    long ncrId = failed.path("ncrId").asLong();
    assertThat(ncrId).isPositive();

    JsonNode disposed =
        performOk(
            authed(
                json(
                    post("/api/quality/ncrs/" + ncrId + "/disposition"),
                    Map.of("disposition", "RETURN", "reason", "整批退回供应商")),
                admin));
    assertThat(disposed.path("status").asText()).isEqualTo("DISPOSITIONED");
    assertThat(disposed.path("disposition").asText()).isEqualTo("RETURN");
    JsonNode closedNcr =
        performOk(authed(post("/api/quality/ncrs/" + ncrId + "/close"), admin));
    assertThat(closedNcr.path("status").asText()).isEqualTo("CLOSED");

    JsonNode capa =
        performOk(
            authed(
                json(
                    post("/api/quality/capas"),
                    Map.of(
                        "ncrId", ncrId,
                        "rootCause", "包装防护不足",
                        "actionPlan", "增加内衬并更新供应商包装规范")),
                admin));
    long capaId = capa.path("id").asLong();
    assertThat(capa.path("status").asText()).isEqualTo("OPEN");
    JsonNode verified =
        performOk(authed(post("/api/quality/capas/" + capaId + "/verify"), admin));
    assertThat(verified.path("status").asText()).isEqualTo("VERIFIED");
    JsonNode closedCapa =
        performOk(authed(post("/api/quality/capas/" + capaId + "/close"), admin));
    assertThat(closedCapa.path("status").asText()).isEqualTo("CLOSED");

    // 合格检验：不生成 NCR
    JsonNode o2 =
        performOk(
            authed(
                json(
                    post("/api/quality/inspection-orders"),
                    Map.of(
                        "companyId", companyId,
                        "planId", planId,
                        "itemId", itemId,
                        "supplierId", supplierId,
                        "lotNo", "LOT-Q2",
                        "qty", 5)),
                admin));
    JsonNode passed =
        performOk(
            authed(
                json(
                    post("/api/quality/inspection-orders/" + o2.path("id").asLong() + "/results"),
                    Map.of("lines", List.of(Map.of(
                        "name", "外观", "measuredValue", "OK", "result", "PASS")))),
                admin));
    assertThat(passed.path("status").asText()).isEqualTo("PASSED");
    assertThat(passed.path("ncrId").isNull()).isTrue();
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/quality/inspection-orders/" + o2.path("id").asLong() + "/results"),
                    Map.of("lines", List.of(Map.of(
                        "name", "外观", "measuredValue", "OK", "result", "PASS")))),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2704));

    // 证书：10 天后到期 → EXPIRING
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    performOk(
        authed(
            json(
                post("/api/quality/certificates"),
                Map.of(
                    "companyId", companyId,
                    "supplierId", supplierId,
                    "certType", "ISO9001",
                    "certNo", "CERT-1",
                    "validFrom", today.minusDays(300).toString(),
                    "validTo", today.plusDays(10).toString())),
            admin));
    JsonNode expiring =
        performOk(
            authed(
                get("/api/quality/certificates/expiring")
                    .param("companyId", String.valueOf(companyId))
                    .param("days", "30"),
                admin));
    assertThat(expiring).hasSize(1);
    assertThat(expiring.get(0).path("effectiveStatus").asText()).isEqualTo("EXPIRING");
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "QC 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", code);
  }

  private long createItem(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/items"),
                Map.of(
                    "code", code,
                    "name", "QC 物料 " + code,
                    "itemType", "RAW",
                    "baseUom", "EA",
                    "lotControlled", true,
                    "serialControlled", false,
                    "valuationMethod", "AVCO")),
            token));
    return idOf("item", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/suppliers"),
                Map.of("code", code, "name", "QC 供应商 " + code, "category", "电子")),
            token));
    return idOf("supplier", code);
  }

  private long idOf(String table, String code) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException(table + " 不存在：" + code);
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
