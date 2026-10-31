package com.spe.context.system.internal.setting;

import com.spe.integration.support.TestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AlertRules;
import com.spe.integration.AbstractIntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预警规则设置（M5 SM3/W2，22 §2 D9）：默认值不落库、保存校验、审计、缓存失效、权限。
 *
 * <p>缓存是进程内单例、不随事务回滚（04-041 的同族）：`@AfterEach` 显式清缓存，避免把测试值带到下一条用例。
 */
@Transactional
class AlertRuleSettingsIntegrationTest extends AbstractIntegrationTest {

  private static final String KEY = "alert.rules";

  @Autowired private SettingService settingService;

  @Autowired private CacheManager cacheManager;

  @AfterEach
  void clearCaches() {
    for (String name : cacheManager.getCacheNames()) {
      var cache = cacheManager.getCache(name);
      if (cache != null) {
        cache.clear();
      }
    }
  }

  @Test
  @DisplayName("默认规则：代码默认值、不落库")
  void defaultsAreHeldByCodeAndNotPersisted() {
    assertThat(settingService.alertRules()).isEqualTo(AlertRules.defaults());
    Long rows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_setting WHERE setting_key = ?", Long.class, KEY);
    assertThat(rows).as("键缺失时不该落库（10 §E9）").isZero();
  }

  @Test
  @DisplayName("保存：写库 + 审计 + 清缓存；非法值由服务层转 2034")
  void savePersistsAuditsAndEvictsCache() throws Exception {
    String token = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("enabled", true);
    body.put("totalThreshold", 80);
    body.put("keyDimensionThreshold", 75);
    body.put("trendPeriods", 3);
    body.put("trendDrop", 6);

    mockMvc
        .perform(authed(json(put("/api/settings/alert-rules"), body), token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.totalThreshold").value(80));

    Long rows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sys_setting WHERE setting_key = ?", Long.class, KEY);
    assertThat(rows).as("保存后必须落库").isEqualTo(1);
    assertThat(settingService.alertRules())
        .as("缓存已失效，读到的是新值")
        .isEqualTo(new AlertRules(true, 80, 75, 3, 6));

    Long audits =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'SETTING_UPDATE' AND detail LIKE ?",
            Long.class,
            "%alert.rules%");
    assertThat(audits).as("规则保存必须留痕").isGreaterThanOrEqualTo(1);

    assertThatThrownBy(
            () -> settingService.updateAlertRules(new AlertRules(true, 80, 75, 9, 6)))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).errorCode())
        .isEqualTo(ErrorCode.ALERT_RULE_INVALID);
  }

  @Test
  @DisplayName("权限：GET/PUT 仅 ADMIN；缺字段 2001、数值越界 2034")
  void onlyAdminCanReadOrWrite() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    mockMvc
        .perform(authed(get("/api/settings/alert-rules"), admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.trendPeriods").value(2));

    insertUser("w2-settings-manager", "MANAGER", "PURCHASE");
    String manager = login("w2-settings-manager", "Admin@12345");
    mockMvc
        .perform(authed(get("/api/settings/alert-rules"), manager))
        .andExpect(status().isForbidden());

    Map<String, Object> outOfRange = new LinkedHashMap<>();
    outOfRange.put("enabled", true);
    outOfRange.put("totalThreshold", 101);
    outOfRange.put("keyDimensionThreshold", 70);
    outOfRange.put("trendPeriods", 2);
    outOfRange.put("trendDrop", 5);
    mockMvc
        .perform(authed(json(put("/api/settings/alert-rules"), outOfRange), admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2034));

    Map<String, Object> missing = new LinkedHashMap<>();
    missing.put("enabled", true);
    missing.put("totalThreshold", 80);
    missing.put("keyDimensionThreshold", 75);
    missing.put("trendDrop", 6);
    mockMvc
        .perform(authed(json(put("/api/settings/alert-rules"), missing), admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
  }

  private void insertUser(String username, String role, String department) { TestFixtures.insertUser(jdbcTemplate, username, role, department); }
}
