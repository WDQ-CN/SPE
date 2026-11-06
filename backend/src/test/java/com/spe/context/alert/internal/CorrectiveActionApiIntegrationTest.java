package com.spe.context.alert.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.spe.integration.AbstractIntegrationTest;
import com.spe.integration.support.TestFixtures;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * W3：整改 REST 契约、角色/可见性与审计。
 *
 * <p>这些用例走完整安全链与真实 MySQL：角色 403、EVALUATOR 只见本人、写接口返回最新 version、
 * 以及审计动作码都必须在 HTTP 层可观察，而不是只在 Service 单测里成立。
 */
@Transactional
class CorrectiveActionApiIntegrationTest extends AbstractIntegrationTest {

  @Test
  @DisplayName("创建→开始→提交→验证：响应带最新 version，预警同事务 HANDLED，六段审计齐全")
  void createAndWalkStateMachineWithAudit() throws Exception {
    long supplierId = insertSupplier("W3-API-SUP-1");
    long managerId = insertUser("w3-api-mgr-1", "MANAGER", "W3 经理一");
    long ownerId = insertUser("w3-api-own-1", "EVALUATOR", "W3 负责人一");
    long dimensionId = insertDimension("来料质量");
    long alertId = insertAlert(supplierId, "UNREAD", dimensionId, "68.00", "70.00", 88001L);
    long legacyAlertId = insertAlert(supplierId, "UNREAD", null, null, null, 88002L);

    String manager = login("w3-api-mgr-1", "Admin@12345");
    String owner = login("w3-api-own-1", "Admin@12345");

    String alertList =
        mockMvc
            .perform(authed(get("/api/alerts").param("status", "UNREAD").param("size", "10"), manager))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat((List<Boolean>) JsonPath.read(
            alertList, "$.data.list[?(@.id == " + alertId + ")].actionable"))
        .containsExactly(true);
    assertThat((List<Boolean>) JsonPath.read(
            alertList, "$.data.list[?(@.id == " + legacyAlertId + ")].actionable"))
        .containsExactly(false);

    Map<String, Object> createBody = new LinkedHashMap<>();
    createBody.put("ownerId", ownerId);
    createBody.put("dueDate", LocalDate.now().plusDays(30).toString());
    createBody.put("measure", "  完善来料检验流程  ");
    createBody.put("expectedResult", "下期维度分不低于 70");

    String createdBody =
        mockMvc
            .perform(
                authed(
                    json(post("/api/alerts/" + alertId + "/corrective-actions"), createBody),
                    manager))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("OPEN"))
            .andExpect(jsonPath("$.data.version").value(0))
            .andExpect(jsonPath("$.data.measure").value("完善来料检验流程"))
            .andExpect(jsonPath("$.data.ownerName").value("W3 负责人一"))
            .andExpect(jsonPath("$.data.dimensionName").value("来料质量"))
            .andExpect(jsonPath("$.data.alertTitle").value("关键维度低于阈值"))
            .andExpect(jsonPath("$.data.supplierCode").value("W3-API-SUP-1"))
            .andExpect(jsonPath("$.data.baselineScore").value(68.00))
            .andExpect(jsonPath("$.data.targetScore").value(70.00))
            .andExpect(jsonPath("$.data.overdue").value(false))
            .andExpect(jsonPath("$.data.auditTrail.length()").value(1))
            .andExpect(jsonPath("$.data.auditTrail[0].action").value("CORRECTIVE_ACTION_CREATE"))
            .andExpect(jsonPath("$.data.auditTrail[0].operatorName").value("w3-api-mgr-1"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long actionId = ((Number) JsonPath.read(createdBody, "$.data.id")).longValue();

    assertThat(statusOfAlert(alertId)).isEqualTo("HANDLED");
    String handledList =
        mockMvc
            .perform(authed(get("/api/alerts").param("status", "HANDLED").param("size", "10"), manager))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Number> linkedActionIds =
        JsonPath.read(handledList, "$.data.list[?(@.id == " + alertId + ")].correctiveActionId");
    assertThat(linkedActionIds).extracting(Number::longValue).containsExactly(actionId);    assertThat((List<String>) JsonPath.read(
            handledList, "$.data.list[?(@.id == " + alertId + ")].correctiveActionStatus"))
        .containsExactly("OPEN");
    assertThat((List<Boolean>) JsonPath.read(
            handledList, "$.data.list[?(@.id == " + alertId + ")].actionable"))
        .containsExactly(false);

    assertAudit("CORRECTIVE_ACTION_CREATE", actionId, managerId, "alertId", "ownerId");

    mockMvc
        .perform(authed(get("/api/corrective-actions"), owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].id").value(actionId));

    Map<String, Object> updateBody = new LinkedHashMap<>();
    updateBody.put("ownerId", ownerId);
    updateBody.put("dueDate", LocalDate.now().plusDays(45).toString());
    updateBody.put("measure", "更新后的整改措施");
    updateBody.put("version", 0);
    mockMvc
        .perform(authed(json(put("/api/corrective-actions/" + actionId), updateBody), owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.measure").value("更新后的整改措施"))
        .andExpect(jsonPath("$.data.version").value(1));

    mockMvc
        .perform(
            authed(
                json(post("/api/corrective-actions/" + actionId + "/start"), Map.of("version", 1)),
                owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.data.version").value(2));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionId + "/submit"),
                    Map.of("version", 2, "comment", "已完成检验流程调整")),
                owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WAITING_VERIFICATION"))
        .andExpect(jsonPath("$.data.submitComment").value("已完成检验流程调整"))
        .andExpect(jsonPath("$.data.version").value(3));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionId + "/verify"),
                    Map.of("version", 3, "pass", true, "comment", "下期报告已核对")),
                manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("VERIFIED"))
        .andExpect(jsonPath("$.data.verifiedByName").value("W3 经理一"))
        .andExpect(jsonPath("$.data.verifyComment").value("下期报告已核对"))
        .andExpect(jsonPath("$.data.version").value(4))
        .andExpect(jsonPath("$.data.auditTrail.length()").value(5))
        .andExpect(jsonPath("$.data.auditTrail[4].action").value("CORRECTIVE_ACTION_VERIFY"));

    assertThat(auditOperator("CORRECTIVE_ACTION_UPDATE", actionId)).isEqualTo(ownerId);
    assertThat(auditOperator("CORRECTIVE_ACTION_START", actionId)).isEqualTo(ownerId);
    assertThat(auditOperator("CORRECTIVE_ACTION_SUBMIT", actionId)).isEqualTo(ownerId);
    assertThat(auditOperator("CORRECTIVE_ACTION_VERIFY", actionId)).isEqualTo(managerId);
    Map<String, Object> verifyAudit =
        jdbcTemplate.queryForMap(
            "SELECT detail FROM audit_log WHERE action = 'CORRECTIVE_ACTION_VERIFY'"
                + " AND target_id = ? ORDER BY id DESC LIMIT 1",
            actionId);
    assertThat(verifyAudit.get("detail").toString())
        .contains("\"verifyYear\"", "\"verifyQuarter\"", "\"verifyScore\"", "\"comment\"");
  }

  @Test
  @DisplayName("角色与可见性：EVALUATOR 只看本人且不能改派/验证；VIEWER 403；ADMIN/MANAGER 可管理")
  void roleAndVisibilityGuards() throws Exception {
    long supplierId = insertSupplier("W3-API-SUP-2");
    long managerId = insertUser("w3-api-mgr-2", "MANAGER", "W3 经理二");
    long evalA = insertUser("w3-api-eval-a", "EVALUATOR", "W3 评估甲");
    long evalB = insertUser("w3-api-eval-b", "EVALUATOR", "W3 评估乙");
    insertUser("w3-api-viewer-2", "VIEWER", "W3 只读");
    long alertA = insertAlert(supplierId, "HANDLED", null, "60.00", "70.00", 88101L);
    long alertB = insertAlert(supplierId, "HANDLED", null, "62.00", "70.00", 88102L);
    long actionA =
        insertAction(alertA, 88101L, supplierId, null, evalA, managerId,
            LocalDate.now().plusDays(10).toString(), "OPEN", 0);
    long actionB =
        insertAction(alertB, 88102L, supplierId, null, evalB, managerId,
            LocalDate.now().plusDays(12).toString(), "OPEN", 0);

    String manager = login("w3-api-mgr-2", "Admin@12345");
    String evalAToken = login("w3-api-eval-a", "Admin@12345");
    String viewer = login("w3-api-viewer-2", "Admin@12345");

    mockMvc
        .perform(authed(get("/api/corrective-actions"), evalAToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].id").value(actionA))
        .andExpect(jsonPath("$.data.list[0].ownerId").value(evalA));
    mockMvc
        .perform(authed(get("/api/corrective-actions/" + actionA), evalAToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ownerId").value(evalA));
    mockMvc
        .perform(authed(get("/api/corrective-actions/" + actionB), evalAToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
    mockMvc
        .perform(
            authed(get("/api/corrective-actions").param("ownerId", String.valueOf(evalB)), evalAToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    Map<String, Object> selfUpdate = new LinkedHashMap<>();
    selfUpdate.put("ownerId", evalA);
    selfUpdate.put("dueDate", LocalDate.now().plusDays(20).toString());
    selfUpdate.put("measure", "本人修改措施");
    selfUpdate.put("version", 0);
    mockMvc
        .perform(authed(json(put("/api/corrective-actions/" + actionA), selfUpdate), evalAToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(1));

    Map<String, Object> reassign = new LinkedHashMap<>();
    reassign.put("ownerId", evalB);
    reassign.put("dueDate", LocalDate.now().plusDays(21).toString());
    reassign.put("measure", "试图改派");
    reassign.put("version", 1);
    mockMvc
        .perform(authed(json(put("/api/corrective-actions/" + actionA), reassign), evalAToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    mockMvc
        .perform(
            authed(
                json(post("/api/corrective-actions/" + actionB + "/start"), Map.of("version", 0)),
                evalAToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionA + "/verify"),
                    Map.of("version", 1, "pass", true)),
                evalAToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionA + "/cancel"),
                    Map.of("version", 1, "reason", "越权取消")),
                evalAToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/alerts/" + alertA + "/corrective-actions"),
                    Map.of(
                        "ownerId", evalA,
                        "dueDate", LocalDate.now().plusDays(5).toString(),
                        "measure", "越权创建")),
                evalAToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    mockMvc
        .perform(authed(get("/api/corrective-actions"), viewer))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));
    mockMvc
        .perform(authed(get("/api/corrective-actions/" + actionA), viewer))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4001));

    mockMvc
        .perform(authed(get("/api/corrective-actions"), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2));
    mockMvc
        .perform(
            authed(
                get("/api/corrective-actions").param("ownerId", String.valueOf(evalA)), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].id").value(actionA));
    mockMvc
        .perform(authed(get("/api/corrective-actions/" + actionB), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.supplierCode").value("W3-API-SUP-2"));

    Map<String, Object> managerReassign = new LinkedHashMap<>();
    managerReassign.put("ownerId", evalA);
    managerReassign.put("dueDate", LocalDate.now().plusDays(25).toString());
    managerReassign.put("measure", "经理改派后的措施");
    managerReassign.put("version", 0);
    mockMvc
        .perform(authed(json(put("/api/corrective-actions/" + actionB), managerReassign), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ownerId").value(evalA))
        .andExpect(jsonPath("$.data.ownerName").value("W3 评估甲"))
        .andExpect(jsonPath("$.data.version").value(1));

    mockMvc
        .perform(
            authed(
                json(post("/api/corrective-actions/" + actionA + "/start"), Map.of("version", 1)),
                manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionA + "/submit"),
                    Map.of("version", 2, "comment", "经理代提交")),
                manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WAITING_VERIFICATION"));

    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionA + "/verify"),
                    Map.of("version", 3, "pass", true, "comment", "管理员复核通过")),
                admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("VERIFIED"))
        .andExpect(jsonPath("$.data.verifiedByName").value("系统管理员"));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionB + "/cancel"),
                    Map.of("version", 1, "reason", "供应商已退出")),
                manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELED"))
        .andExpect(jsonPath("$.data.canceledReason").value("供应商已退出"))
        .andExpect(jsonPath("$.data.version").value(2));

    assertThat(auditOperator("CORRECTIVE_ACTION_UPDATE", actionB)).isEqualTo(managerId);
    assertThat(auditOperator("CORRECTIVE_ACTION_VERIFY", actionA))
        .isEqualTo(userIdOf(ADMIN_USERNAME));
    assertThat(auditOperator("CORRECTIVE_ACTION_CANCEL", actionB)).isEqualTo(managerId);
  }

  @Test
  @DisplayName("错误码：重复/旧预警 2040、负责人非法 2041、不存在 2038、非法迁移 2039、版本 3001")
  void creationAndTransitionErrorCodes() throws Exception {
    long supplierId = insertSupplier("W3-API-SUP-3");
    long managerId = insertUser("w3-api-mgr-3", "MANAGER", "W3 经理三");
    long ownerId = insertUser("w3-api-own-3", "EVALUATOR", "W3 负责人三");
    long viewerId = insertUser("w3-api-viewer-3", "VIEWER", "W3 只读三");
    long alertId = insertAlert(supplierId, "UNREAD", null, "60.00", "70.00", 88201L);
    long legacyAlertId = insertAlert(supplierId, "UNREAD", null, null, null, 88202L);
    long ownerAlertId = insertAlert(supplierId, "UNREAD", null, "61.00", "70.00", 88203L);

    String manager = login("w3-api-mgr-3", "Admin@12345");
    String owner = login("w3-api-own-3", "Admin@12345");

    Map<String, Object> createBody = createBody(ownerId, 30, "整改措施");
    String created =
        mockMvc
            .perform(
                authed(
                    json(post("/api/alerts/" + alertId + "/corrective-actions"), createBody),
                    manager))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long actionId = ((Number) JsonPath.read(created, "$.data.id")).longValue();

    mockMvc
        .perform(
            authed(
                json(post("/api/alerts/" + alertId + "/corrective-actions"), createBody),
                manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2040));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/alerts/" + legacyAlertId + "/corrective-actions"),
                    createBody),
                manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2040));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/alerts/" + ownerAlertId + "/corrective-actions"),
                    createBody(viewerId, 30, "错误负责人")),
                manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2041));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/alerts/99999999/corrective-actions"),
                    createBody(ownerId, 30, "不存在")),
                manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2032));

    mockMvc
        .perform(authed(get("/api/corrective-actions/99999999"), manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2038));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionId + "/submit"),
                    Map.of("version", 0)),
                owner))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2039));
    mockMvc
        .perform(
            authed(
                json(post("/api/corrective-actions/" + actionId + "/start"), Map.of("version", 99)),
                owner))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3001));

    mockMvc
        .perform(
            authed(
                json(post("/api/corrective-actions/" + actionId + "/start"), Map.of("version", 0)),
                owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(1));

    Map<String, Object> staleUpdate = new LinkedHashMap<>();
    staleUpdate.put("ownerId", ownerId);
    staleUpdate.put("dueDate", LocalDate.now().plusDays(31).toString());
    staleUpdate.put("measure", "旧版本修改");
    staleUpdate.put("version", 0);
    mockMvc
        .perform(authed(json(put("/api/corrective-actions/" + actionId), staleUpdate), owner))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(3001));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionId + "/verify"),
                    Map.of("version", 1, "pass", true)),
                manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2039));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionId + "/submit"),
                    Map.of("version", 1, "comment", "已提交")),
                owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WAITING_VERIFICATION"));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionId + "/verify"),
                    Map.of("version", 2, "pass", true)),
                manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionId + "/verify"),
                    Map.of("version", 2, "pass", false, "comment", "继续整改")),
                manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REOPENED"))
        .andExpect(jsonPath("$.data.version").value(3));

    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionId + "/cancel"),
                    Map.of("version", 3, "reason", "")),
                manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionId + "/cancel"),
                    Map.of("version", 3, "reason", "终止")),
                manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELED"))
        .andExpect(jsonPath("$.data.version").value(4));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/corrective-actions/" + actionId + "/cancel"),
                    Map.of("version", 4, "reason", "重复取消")),
                manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2039));

    mockMvc
        .perform(authed(get("/api/corrective-actions").param("status", "BAD"), manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
    mockMvc
        .perform(authed(get("/api/corrective-actions").param("supplierId", "-1"), manager))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2001));
    mockMvc
        .perform(authed(get("/api/corrective-actions").param("ownerId", "99999999"), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(0));
  }

  @Test
  @DisplayName("列表：状态/供应商/负责人/超期筛选、分页与投影字段；EVALUATOR 只统计本人")
  void listFiltersAndProjection() throws Exception {
    long supplierA = insertSupplier("W3-API-SUP-4A");
    long supplierB = insertSupplier("W3-API-SUP-4B");
    long managerId = insertUser("w3-api-mgr-4", "MANAGER", "W3 经理四");
    long evalA = insertUser("w3-api-eval-4a", "EVALUATOR", "W3 评估四甲");
    long evalB = insertUser("w3-api-eval-4b", "EVALUATOR", "W3 评估四乙");
    long dimensionId = insertDimension("交付准时率");
    LocalDate today = LocalDate.now();

    long alert1 = insertAlert(supplierA, "HANDLED", null, "60.00", "70.00", 88301L);
    long action1 =
        insertAction(alert1, 88301L, supplierA, null, evalA, managerId,
            today.minusDays(1).toString(), "OPEN", 0);
    long alert2 = insertAlert(supplierA, "HANDLED", null, "62.00", "70.00", 88302L);
    long action2 =
        insertAction(alert2, 88302L, supplierA, null, evalB, managerId,
            today.plusDays(10).toString(), "IN_PROGRESS", 0);
    long alert3 = insertAlert(supplierB, "HANDLED", dimensionId, "65.00", "70.00", 88303L);
    long action3 =
        insertAction(alert3, 88303L, supplierB, dimensionId, evalA, managerId,
            today.minusDays(2).toString(), "WAITING_VERIFICATION", 0);
    jdbcTemplate.update(
        "UPDATE corrective_action SET baseline_score = 65.00, target_score = 70.00,"
            + " verify_year = 2026, verify_quarter = 2,"
            + " verify_score = 75.00, verify_outcome = 'IMPROVED', verify_comment = '下期已改善',"
            + " verified_by = 1, verified_at = CURRENT_TIMESTAMP(3)"
            + " WHERE id = ?",
        action3);
    long alert4 = insertAlert(supplierB, "HANDLED", null, "66.00", "70.00", 88304L);
    long action4 =
        insertAction(alert4, 88304L, supplierB, null, evalB, managerId,
            today.minusDays(5).toString(), "VERIFIED", 0);
    long alert5 = insertAlert(supplierA, "HANDLED", null, "67.00", "70.00", 88305L);
    insertAction(alert5, 88305L, supplierA, null, evalA, managerId,
        today.minusDays(3).toString(), "CANCELED", 0);

    String manager = login("w3-api-mgr-4", "Admin@12345");
    String evalAToken = login("w3-api-eval-4a", "Admin@12345");

    mockMvc
        .perform(authed(get("/api/corrective-actions"), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(5));
    mockMvc
        .perform(authed(get("/api/corrective-actions").param("status", "OPEN"), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.list[0].id").value(action1))
        .andExpect(jsonPath("$.data.list[0].reverifyPending").value(false));
    mockMvc
        .perform(
            authed(
                get("/api/corrective-actions").param("supplierId", String.valueOf(supplierA)),
                manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(3));
    String ownerFilteredBody =
        mockMvc
            .perform(
                authed(
                    get("/api/corrective-actions").param("ownerId", String.valueOf(evalB)),
                    manager))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<Number> ownerFilteredIds = JsonPath.read(ownerFilteredBody, "$.data.list[*].id");
    assertThat(ownerFilteredIds)
        .extracting(Number::longValue)
        .containsExactlyInAnyOrder(action2, action4);
    mockMvc
        .perform(authed(get("/api/corrective-actions").param("overdue", "true"), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2));
    mockMvc
        .perform(authed(get("/api/corrective-actions").param("overdue", "false"), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(3));

    String waitingBody =
        mockMvc
            .perform(
                authed(
                    get("/api/corrective-actions")
                        .param("status", "WAITING_VERIFICATION")
                        .param("supplierId", String.valueOf(supplierB)),
                    manager))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.list[0].id").value(action3))
            .andExpect(jsonPath("$.data.list[0].supplierCode").value("W3-API-SUP-4B"))
            .andExpect(jsonPath("$.data.list[0].dimensionName").value("交付准时率"))
            .andExpect(jsonPath("$.data.list[0].baselineScore").value(65.00))
            .andExpect(jsonPath("$.data.list[0].targetScore").value(70.00))
            .andExpect(jsonPath("$.data.list[0].ownerName").value("W3 评估四甲"))
            .andExpect(jsonPath("$.data.list[0].verifyYear").value(2026))
            .andExpect(jsonPath("$.data.list[0].verifyQuarter").value(2))
            .andExpect(jsonPath("$.data.list[0].verifyScore").value(75.00))
            .andExpect(jsonPath("$.data.list[0].verifyOutcome").value("IMPROVED"))
            .andExpect(jsonPath("$.data.list[0].overdue").value(true))
            .andExpect(jsonPath("$.data.list[0].reverifyPending").value(true))
            .andExpect(jsonPath("$.data.list[0].version").value(0))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> waitingRow =
        JsonPath.read(waitingBody, "$.data.list[0]");
    assertThat(waitingRow).doesNotContainKey("measure");

    mockMvc
        .perform(authed(get("/api/corrective-actions/" + action3), manager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.measure").value("整改措施"))
        .andExpect(jsonPath("$.data.expectedResult").value("预期结果"))
        .andExpect(jsonPath("$.data.alertType").value("KEY_DIMENSION_LOW"))
        .andExpect(jsonPath("$.data.alertTitle").value("关键维度低于阈值"))
        .andExpect(jsonPath("$.data.createdByName").value("W3 经理四"))
        .andExpect(jsonPath("$.data.verifyComment").value("下期已改善"))
        .andExpect(jsonPath("$.data.overdue").value(true));

    mockMvc
        .perform(authed(get("/api/corrective-actions"), evalAToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(3))
        .andExpect(jsonPath("$.data.list[0].ownerId").value(evalA))
        .andExpect(jsonPath("$.data.list[1].ownerId").value(evalA))
        .andExpect(jsonPath("$.data.list[2].ownerId").value(evalA));
    mockMvc
        .perform(
            authed(
                get("/api/corrective-actions").param("page", "2").param("size", "2"),
                evalAToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(3))
        .andExpect(jsonPath("$.data.list.length()").value(1));
  }

  private long insertSupplier(String code) {
    return TestFixtures.insertSupplier(jdbcTemplate, code, "供应商 " + code, "W3 API");
  }

  private long insertUser(String username, String role, String name) {
    return TestFixtures.insertUser(jdbcTemplate, username, role, "QUALITY", name);
  }

  private long insertDimension(String name) {
    jdbcTemplate.update("INSERT INTO template (name) VALUES (?)", "W3 整改模板");
    Long templateId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM template WHERE name = ? ORDER BY id DESC LIMIT 1",
            Long.class,
            "W3 整改模板");
    jdbcTemplate.update(
        "INSERT INTO template_version (template_id, version_no, status) VALUES (?, 1, 'PUBLISHED')",
        templateId);
    Long versionId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM template_version WHERE template_id = ? ORDER BY id DESC LIMIT 1",
            Long.class,
            templateId);
    jdbcTemplate.update(
        "INSERT INTO dimension (template_version_id, name, weight, sort_no, is_key)"
            + " VALUES (?, ?, 100.00, 0, 1)",
        versionId,
        name);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM dimension WHERE template_version_id = ? ORDER BY id DESC LIMIT 1",
        Long.class,
        versionId);
  }

  private long insertAlert(
      long supplierId, String status, Long dimensionId, String baseline, String target, long marker) {
    String type = dimensionId == null ? "TOTAL_LOW" : "KEY_DIMENSION_LOW";
    String title = dimensionId == null ? "总分低于阈值" : "关键维度低于阈值";
    jdbcTemplate.update(
        "INSERT INTO alert (task_supplier_id, supplier_id, year, quarter, type, title, content,"
            + " dimension_id, baseline_score, target_score, status)"
            + " VALUES (?, ?, 2026, 1, ?, ?, '测试预警', ?, ?, ?, ?)",
        marker,
        supplierId,
        type,
        title,
        dimensionId,
        baseline,
        target,
        status);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM alert WHERE task_supplier_id = ?", Long.class, marker);
  }

  private long insertAction(
      long alertId,
      long taskSupplierId,
      long supplierId,
      Long dimensionId,
      long ownerId,
      long createdBy,
      String dueDate,
      String status,
      int version) {
    jdbcTemplate.update(
        "INSERT INTO corrective_action (alert_id, task_supplier_id, supplier_id, dimension_id,"
            + " baseline_year, baseline_quarter, baseline_score, target_score, owner_id, due_date,"
            + " measure, expected_result, status, created_by, version)"
            + " VALUES (?, ?, ?, ?, 2026, 1, 60.00, 70.00, ?, ?, '整改措施', '预期结果', ?, ?, ?)",
        alertId,
        taskSupplierId,
        supplierId,
        dimensionId,
        ownerId,
        dueDate,
        status,
        createdBy,
        version);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM corrective_action WHERE alert_id = ?", Long.class, alertId);
  }

  private Map<String, Object> createBody(long ownerId, int dueDays, String measure) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ownerId", ownerId);
    body.put("dueDate", LocalDate.now().plusDays(dueDays).toString());
    body.put("measure", measure);
    return body;
  }

  private String statusOfAlert(long alertId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM alert WHERE id = ?", String.class, alertId);
  }

  private void assertAudit(
      String action, long targetId, long operatorId, String... detailFragments) {
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT operator_id, operator_name, target_type, target_id, detail FROM audit_log"
                + " WHERE action = ? AND target_id = ? ORDER BY id DESC LIMIT 1",
            action,
            targetId);
    assertThat(((Number) row.get("operator_id")).longValue()).isEqualTo(operatorId);
    assertThat(row.get("target_type")).isEqualTo("CORRECTIVE_ACTION");
    assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(targetId);
    String detail = String.valueOf(row.get("detail"));
    for (String fragment : detailFragments) {
      assertThat(detail).contains(fragment);
    }
  }

  private long auditOperator(String action, long targetId) {
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT operator_id FROM audit_log WHERE action = ? AND target_id = ?"
                + " ORDER BY id DESC LIMIT 1",
            action,
            targetId);
    return ((Number) row.get("operator_id")).longValue();
  }
}
