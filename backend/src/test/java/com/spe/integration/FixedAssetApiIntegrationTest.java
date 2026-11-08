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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E5 固定资产：资本化、直线折旧、处置与财务过账（docs/erp/38）。 */
@Transactional
class FixedAssetApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("资产资本化、折旧期间守卫、处置损益与财务来源单")
  void assetLifecycle() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "FA-CO");
    configureFinance(companyId);
    JsonNode asset =
        performOk(
            authed(
                json(
                    post("/api/assets"),
                    Map.of(
                        "companyId", companyId,
                        "category", "电子设备",
                        "name", "贴片机",
                        "cost", 1200,
                        "salvageValue", 0,
                        "usefulLifeMonths", 12,
                        "depreciationMethod", "STRAIGHT_LINE")),
                admin));
    long assetId = asset.path("id").asLong();
    assertThat(asset.path("status").asText()).isEqualTo("PLANNED");

    JsonNode capitalized =
        performOk(authed(post("/api/assets/" + assetId + "/capitalize"), admin));
    assertThat(capitalized.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(capitalized.path("netBookValue").decimalValue()).isEqualByComparingTo("1200.0000");
    assertThat(journalCount("ASSET_CAPITALIZATION")).isEqualTo(1);

    JsonNode depreciated =
        performOk(
            authed(
                json(post("/api/assets/" + assetId + "/depreciate"), Map.of("period", "2026-01")),
                admin));
    assertThat(depreciated.path("accumulatedDepreciation").decimalValue())
        .isEqualByComparingTo("100.0000");
    assertThat(depreciated.path("netBookValue").decimalValue()).isEqualByComparingTo("1100.0000");
    assertThat(journalCount("ASSET_DEPRECIATION")).isEqualTo(1);

    mockMvc
        .perform(
            authed(
                json(post("/api/assets/" + assetId + "/depreciate"), Map.of("period", "2026-01")),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2803));
    mockMvc
        .perform(
            authed(
                json(post("/api/assets/" + assetId + "/depreciate"), Map.of("period", "2025-12")),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2803));

    JsonNode disposed =
        performOk(
            authed(
                json(post("/api/assets/" + assetId + "/dispose"), Map.of("proceeds", 900)),
                admin));
    assertThat(disposed.path("status").asText()).isEqualTo("DISPOSED");
    assertThat(disposed.path("disposalGainLoss").decimalValue())
        .isEqualByComparingTo("-200.0000");
    mockMvc
        .perform(
            authed(
                json(post("/api/assets/" + assetId + "/dispose"), Map.of("proceeds", 0)),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2802));

    JsonNode page =
        performOk(
            authed(
                get("/api/assets").param("companyId", String.valueOf(companyId)), admin));
    assertThat(page.path("total").asLong()).isEqualTo(1);
  }

  @Test
  @DisplayName("折旧逐期过账：第二期不能被来源唯一键静默吞掉")
  void depreciationEachPeriodPosts() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "FA2-CO");
    configureFinance(companyId);
    JsonNode asset =
        performOk(
            authed(
                json(
                    post("/api/assets"),
                    Map.of(
                        "companyId", companyId,
                        "category", "电子设备",
                        "name", "分光机",
                        "cost", 1200,
                        "salvageValue", 0,
                        "usefulLifeMonths", 12,
                        "depreciationMethod", "STRAIGHT_LINE")),
                admin));
    long assetId = asset.path("id").asLong();
    performOk(authed(post("/api/assets/" + assetId + "/capitalize"), admin));

    performOk(
        authed(
            json(post("/api/assets/" + assetId + "/depreciate"), Map.of("period", "2026-01")),
            admin));
    performOk(
        authed(
            json(post("/api/assets/" + assetId + "/depreciate"), Map.of("period", "2026-02")),
            admin));

    assertThat(journalCount("ASSET_DEPRECIATION")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finance_journal_line l"
                    + " JOIN finance_journal_entry e ON e.id = l.entry_id"
                    + " WHERE e.source_type = 'ASSET_DEPRECIATION'",
                Long.class))
        .isEqualTo(4L);
  }

  private void configureFinance(long companyId) {
    long fixedAsset = insertAccount(companyId, "1601", "固定资产", "ASSET");
    long accumulated = insertAccount(companyId, "1602", "累计折旧", "ASSET");
    long bank = insertAccount(companyId, "1002", "银行存款", "ASSET");
    long expense = insertAccount(companyId, "6601", "折旧费用", "EXPENSE");
    insertMapping(companyId, "ASSET_CAPITALIZATION", fixedAsset, bank);
    insertMapping(companyId, "ASSET_DEPRECIATION", expense, accumulated);
  }

  private long insertAccount(long companyId, String code, String name, String type) {
    jdbcTemplate.update(
        "INSERT INTO gl_account (company_id, code, name, account_type, normal_balance,"
            + " requires_cost_center, status, version) VALUES (?, ?, ?, ?, 'DEBIT', 0, 'ACTIVE', 0)",
        companyId,
        code,
        name,
        type);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM gl_account WHERE company_id = ? AND code = ?", Long.class, companyId, code);
  }

  private void insertMapping(long companyId, String eventType, long debit, long credit) {
    jdbcTemplate.update(
        "INSERT INTO account_mapping (company_id, event_type, dimension, debit_account_id,"
            + " credit_account_id, tax_account_id, valid_from, status, version)"
            + " VALUES (?, ?, '', ?, ?, NULL, ?, 'ACTIVE', 0)",
        companyId,
        eventType,
        debit,
        credit,
        java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusYears(1)));
  }

  private long journalCount(String sourceType) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM finance_journal_entry WHERE source_type = ?",
        Long.class,
        sourceType);
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "FA 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    Long id =
        jdbcTemplate.queryForObject("SELECT id FROM company WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException("公司不存在");
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
