package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.api.Role;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/** E1-W1a：组织与物料主数据的 REST/层级/版本/审计/UoM/越权验收（docs/erp/52）。 */
@Transactional
class MasterDataApiIntegrationTest extends AbstractIntegrationTest {

  private long createCompany(String token, String code) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/companies"),
                    Map.of(
                        "code", code,
                        "name", "测试公司 " + code,
                        "baseCurrency", "CNY",
                        "taxNo", "91310000TEST",
                        "country", "CN")),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.taxNo").value("****TEST"));
    return idOf("company", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/plants"),
                    Map.of("companyId", companyId, "code", code, "name", "测试工厂 " + code)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.companyId").value(companyId));
    return idOf("plant", code);
  }

  private long createWarehouse(String token, long plantId, String code) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/warehouses"),
                    Map.of(
                        "plantId", plantId,
                        "code", code,
                        "name", "测试仓库 " + code,
                        "warehouseType", "STANDARD")),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.plantId").value(plantId));
    return idOf("warehouse", code);
  }

  private long createItem(String token, String code) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "测试物料 " + code);
    body.put("spec", "规格-A");
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("lotControlled", true);
    body.put("serialControlled", false);
    body.put("valuationMethod", "AVCO");
    mockMvc
        .perform(authed(json(post("/api/masterdata/items"), body), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.lotControlled").value(true));
    return idOf("item", code);
  }

  private long idOf(String table, String code) {
    Long id = jdbcTemplate.queryForObject("SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException(table + " 不存在：" + code);
    }
    return id;
  }

  private String loginAs(String adminToken, String username, Role role) throws Exception {
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/users"),
                    Map.of(
                        "username", username,
                        "password", DEFAULT_PASSWORD,
                        "name", "E1 主数据用例账号",
                        "role", role.name(),
                        "department", "QUALITY")),
                adminToken))
        .andExpect(status().isOk());
    return login(username, DEFAULT_PASSWORD);
  }

  @Test
  @DisplayName("组织/物料创建、列表与审计：company→plant→warehouse→item 可追溯")
  void createOrganizationAndItem() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(token, "E1T-CO-1");
    long plantId = createPlant(token, companyId, "E1T-PL-1");
    createWarehouse(token, plantId, "E1T-WH-1");
    createItem(token, "E1T-IT-1");

    mockMvc
        .perform(authed(get("/api/masterdata/companies").param("keyword", "E1T-CO"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].code").value("E1T-CO-1"));

    mockMvc
        .perform(
            authed(
                get("/api/masterdata/plants").param("companyId", String.valueOf(companyId)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].code").value("E1T-PL-1"));

    mockMvc
        .perform(
            authed(
                get("/api/masterdata/warehouses").param("plantId", String.valueOf(plantId)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].code").value("E1T-WH-1"));

    mockMvc
        .perform(authed(get("/api/masterdata/items").param("keyword", "E1T-IT"), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].code").value("E1T-IT-1"))
        .andExpect(jsonPath("$.data.list[0].baseUom").value("EA"));

    assertThat(auditRows("COMPANY_CREATE")).hasSize(1);
    assertThat(auditRows("PLANT_CREATE")).hasSize(1);
    assertThat(auditRows("WAREHOUSE_CREATE")).hasSize(1);
    assertThat(auditRows("ITEM_CREATE")).hasSize(1);
  }

  @Test
  @DisplayName("唯一键与层级：重复编码 2102/2104/2106/2108；缺上级 2101/2103；停用上级 2110")
  void duplicateCodesAndHierarchy() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(token, "E1T-CO-2");
    long plantId = createPlant(token, companyId, "E1T-PL-2");
    createItem(token, "E1T-IT-2");

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/companies"),
                    Map.of("code", "E1T-CO-2", "name", "重复", "baseCurrency", "CNY", "country", "CN")),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2102));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/plants"),
                    Map.of("companyId", companyId, "code", "E1T-PL-2", "name", "重复")),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2104));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/warehouses"),
                    Map.of(
                        "plantId", plantId,
                        "code", "E1T-WH-2",
                        "name", "唯一仓库",
                        "warehouseType", "STANDARD")),
                token))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/warehouses"),
                    Map.of(
                        "plantId", plantId,
                        "code", "E1T-WH-2",
                        "name", "重复",
                        "warehouseType", "STANDARD")),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2106));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/items"),
                    Map.of(
                        "code", "E1T-IT-2",
                        "name", "重复",
                        "itemType", "RAW",
                        "baseUom", "EA",
                        "valuationMethod", "AVCO")),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2108));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/plants"),
                    Map.of("companyId", 999999L, "code", "E1T-PL-X", "name", "缺公司")),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2101));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/warehouses"),
                    Map.of(
                        "plantId", 999999L,
                        "code", "E1T-WH-X",
                        "name", "缺工厂",
                        "warehouseType", "STANDARD")),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2103));

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/masterdata/companies/" + companyId + "/status"),
                    Map.of("status", "INACTIVE", "version", 0)),
                token))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/plants"),
                    Map.of("companyId", companyId, "code", "E1T-PL-X", "name", "停用公司")),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2110));
  }

  @Test
  @DisplayName("版本/状态/审计：旧版本 3001；重复目标状态 2112；审计只记真实变化")
  void versionStatusAndAudit() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(token, "E1T-CO-3");

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/masterdata/companies/" + companyId),
                    Map.of(
                        "code", "E1T-CO-3",
                        "name", "新名称",
                        "baseCurrency", "CNY",
                        "taxNo", "****TEST",
                        "country", "CN",
                        "version", 0)),
                token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(1));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT tax_no FROM company WHERE id = ?", String.class, companyId))
        .isEqualTo("91310000TEST");

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/masterdata/companies/" + companyId),
                    Map.of(
                        "code", "E1T-CO-3",
                        "name", "再次改名",
                        "baseCurrency", "CNY",
                        "taxNo", "91310000TEST",
                        "country", "CN",
                        "version", 0)),
                token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3001));

    Map<String, Object> latest =
        jdbcTemplate.queryForMap(
            "SELECT detail FROM audit_log WHERE action = 'COMPANY_UPDATE' AND target_id = ?"
                + " ORDER BY id DESC LIMIT 1",
            companyId);
    assertThat(latest.get("detail").toString()).contains("新名称").doesNotContain("baseCurrency");

    mockMvc
        .perform(
            authed(
                json(
                    put("/api/masterdata/companies/" + companyId + "/status"),
                    Map.of("status", "INACTIVE", "version", 1)),
                token))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            authed(
                json(
                    put("/api/masterdata/companies/" + companyId + "/status"),
                    Map.of("status", "INACTIVE", "version", 2)),
                token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2112));
    assertThat(auditRows("COMPANY_STATUS_CHANGE")).hasSize(1);
  }

  @Test
  @DisplayName("物料 UoM：新增/列表/重复 2109/删除；EVALUATOR 403")
  void itemUomAndForbidden() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long itemId = createItem(admin, "E1T-IT-4");

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/items/" + itemId + "/uoms"),
                    Map.of(
                        "fromUom", "BOX",
                        "toUom", "EA",
                        "numerator", 10,
                        "denominator", 1)),
                admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.fromUom").value("BOX"));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/masterdata/items/" + itemId + "/uoms"),
                    Map.of(
                        "fromUom", "BOX",
                        "toUom", "EA",
                        "numerator", 10,
                        "denominator", 1)),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2109));
    mockMvc
        .perform(authed(get("/api/masterdata/items/" + itemId + "/uoms"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
    Long uomId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM item_uom_conversion WHERE item_id = ?", Long.class, itemId);
    mockMvc
        .perform(authed(delete("/api/masterdata/items/" + itemId + "/uoms/" + uomId), admin))
        .andExpect(status().isOk());
    mockMvc
        .perform(authed(get("/api/masterdata/items/" + itemId + "/uoms"), admin))
        .andExpect(jsonPath("$.data.length()").value(0));
    assertThat(auditRows("ITEM_UOM_ADD")).hasSize(1);
    assertThat(auditRows("ITEM_UOM_REMOVE")).hasSize(1);

    String evaluator = loginAs(admin, "e1t-eval-1", Role.EVALUATOR);
    mockMvc
        .perform(authed(get("/api/masterdata/companies"), evaluator))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
  }
}
