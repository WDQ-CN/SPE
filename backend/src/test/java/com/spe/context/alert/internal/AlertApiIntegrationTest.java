package com.spe.context.alert.internal;

import com.spe.integration.support.TestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.spe.integration.AbstractIntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预警列表与处理（M5 W2/W3，01 W3）：状态过滤、SUPERSEDED 不列出、处理守卫、权限、分页。
 */
@Transactional
class AlertApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("列表：VIEWER 可读、默认排除 SUPERSEDED、状态过滤与分页；EVALUATOR 403")
  void listIsScopedAndFiltered() throws Exception {
    long supplierId = insertSupplier("W2-API-SUP");
    long managerId = insertUser("w2-api-mgr", "MANAGER");
    long unreadId = insertAlert(supplierId, "UNREAD", null, null);
    long handledId = insertAlert(supplierId, "HANDLED", managerId, "已约谈");
    insertAlert(supplierId, "SUPERSEDED", null, null);

    insertUser("w2-api-viewer", "VIEWER");
    String viewer = login("w2-api-viewer", "Admin@12345");    mockMvc
        .perform(authed(get("/api/alerts"), viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2))
        .andExpect(jsonPath("$.data.list.length()").value(2))
        .andExpect(jsonPath("$.data.list[0].supplierCode").value("W2-API-SUP"));

    mockMvc
        .perform(authed(get("/api/alerts").param("status", "UNREAD"), viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].id").value(unreadId));

    mockMvc
        .perform(authed(get("/api/alerts").param("status", "HANDLED"), viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].id").value(handledId));

    mockMvc
        .perform(authed(get("/api/alerts").param("status", "SUPERSEDED"), viewer))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));

    mockMvc
        .perform(authed(get("/api/alerts").param("page", "1").param("size", "1"), viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2))
        .andExpect(jsonPath("$.data.list.length()").value(1));

    insertUser("w2-api-eval", "EVALUATOR");
    String evaluator = login("w2-api-eval", "Admin@12345");
    mockMvc
        .perform(authed(get("/api/alerts"), evaluator))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
  }

  @Test
  @DisplayName("处理：VIEWER 403；MANAGER 成功（备注/处理人）；重复 2033；不存在 2032")
  void handleIsGuarded() throws Exception {
    long supplierId = insertSupplier("W2-API-HANDLE");
    long managerId = insertUser("w2-api-handler", "MANAGER");
    long alertId = insertAlert(supplierId, "UNREAD", null, null);
    String manager = login("w2-api-handler", "Admin@12345");
    insertUser("w2-api-viewer2", "VIEWER");
    String viewer = login("w2-api-viewer2", "Admin@12345");

    mockMvc
        .perform(authed(json(put("/api/alerts/" + alertId + "/handle"), Map.of("comment", "越权")), viewer))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("comment", "已联系供应商整改");
    mockMvc
        .perform(authed(json(put("/api/alerts/" + alertId + "/handle"), body), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("HANDLED"))
        .andExpect(jsonPath("$.data.handledBy").value(managerId))
        .andExpect(jsonPath("$.data.handledComment").value("已联系供应商整改"))
        .andExpect(jsonPath("$.data.handledByName").value("W2 w2-api-handler"));

    mockMvc
        .perform(authed(json(put("/api/alerts/" + alertId + "/handle"), body), manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2033));

    mockMvc
        .perform(authed(json(put("/api/alerts/99999999/handle"), body), manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2032));
  }

  @Test
  @DisplayName("列表翻页：逐页拼起来必须等于按 created_at 倒序的完整序列（V1002 派生表改写的核心语义）")
  void pagesFollowCreatedOrderAcrossPages() throws Exception {
    long supplierId = insertSupplier("W2-PAGE-SUP");
    for (int i = 0; i < 7; i++) {
      insertAlert(supplierId, "UNREAD", null, null);
    }
    List<Long> ids =
        jdbcTemplate.queryForList(
            "SELECT id FROM alert WHERE supplier_id = ? ORDER BY id", Long.class, supplierId);
    assertThat(ids).as("7 条测试预警应全部落在同一供应商下").hasSize(7);
    // 刻意让 id 序与 created_at 序**相反**：id 最小的那条时间最新。
    // 只按 id 排序、或改写后丢了外层 ORDER BY，本用例就会红。
    for (int i = 0; i < ids.size(); i++) {
      jdbcTemplate.update(
          "UPDATE alert SET created_at = DATE_ADD('2026-01-01 00:00:00.000', INTERVAL ? SECOND)"
              + " WHERE id = ?",
          ids.size() - i,
          ids.get(i));
    }

    insertUser("w2-page-viewer", "VIEWER");
    String viewer = login("w2-page-viewer", "Admin@12345");
    for (int page = 1; page <= 3; page++) {
      var result =
          mockMvc
              .perform(
                  authed(
                      get("/api/alerts")
                          .param("page", String.valueOf(page))
                          .param("size", "3"),
                      viewer))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.total").value(7))
              .andReturn();
      String list = result.getResponse().getContentAsString();
      int expectedSize = page == 3 ? 1 : 3;
      Integer listed = JsonPath.read(list, "$.data.list.length()");
      assertThat(listed).as("第 %d 页条数", page).isEqualTo(expectedSize);
      for (int i = 0; i < expectedSize; i++) {
        long expected = ids.get((page - 1) * 3 + i);
        Number actual = JsonPath.read(list, "$.data.list[" + i + "].id");
        assertThat(actual.longValue())
            .as("第 %d 页第 %d 行应是 id=%d", page, i + 1, expected)
            .isEqualTo(expected);
      }
    }
  }

  private long insertSupplier(String code) { return TestFixtures.insertSupplier(jdbcTemplate, code, "供应商 " + code, "W2 API"); }

  private long insertUser(String username, String role) { return TestFixtures.insertUser(jdbcTemplate, username, role, "QUALITY", "W2 " + username); }

  private long insertAlert(long supplierId, String status, Long handledBy, String comment) {
    jdbcTemplate.update(
        "INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content,"
            + " status, handled_by, handled_comment, handled_at)"
            + " VALUES (?, ?, 2026, 2, 'TOTAL_LOW', '总分低于阈值', ?, ?, ?, ?,"
            + " CASE WHEN ? IS NULL THEN NULL ELSE CURRENT_TIMESTAMP(3) END)",
        92001L,
        supplierId,
        "供应商测试预警",
        status,
        handledBy,
        comment,
        handledBy);
    return jdbcTemplate.queryForObject(
        "SELECT MAX(id) FROM alert WHERE supplier_id = ?", Long.class, supplierId);
  }
}
