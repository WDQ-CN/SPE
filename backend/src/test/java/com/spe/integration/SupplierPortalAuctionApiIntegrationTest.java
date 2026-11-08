package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spe.context.system.api.Role;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E2：供应商门户认证、竞价出价、密封/防狙击/幂等与隔离（docs/erp/55、56）。 */
@Transactional
class SupplierPortalAuctionApiIntegrationTest extends AbstractIntegrationTest {

  private record Setup(
      long companyId,
      long plantId,
      long itemId,
      long supplierId,
      String portalToken,
      String refreshToken) {}

  private Setup setup(boolean createPortalUser) throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "PA-CO-1");
    long plantId = createPlant(admin, companyId, "PA-PL-1");
    long itemId = createItem(admin, "PA-IT-1");
    long supplierId = createSupplier(admin, "PA-SUP-1");
    String portalToken = null;
    String refreshToken = null;
    if (createPortalUser) {
      createPortalUser(admin, supplierId, "portal-buyer");
      JsonNode login = portalLogin("portal-buyer", DEFAULT_PASSWORD);
      portalToken = login.path("accessToken").asText();
      refreshToken = login.path("refreshToken").asText();
    }
    return new Setup(companyId, plantId, itemId, supplierId, portalToken, refreshToken);
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

  private JsonNode portalLogin(String loginName, String password) throws Exception {
    return performOk(
        mockMvc.perform(
            json(
                post("/api/supplier-portal/auth/login"),
                Map.of("loginName", loginName, "password", password))));
  }

  private JsonNode createAuction(
      String adminToken,
      Setup setup,
      boolean sealed,
      LocalDateTime endAt,
      int antiSnipingSeconds) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "连接器年度竞价");
    body.put("companyId", setup.companyId());
    body.put("currency", "CNY");
    body.put("startAt", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1).toString());
    body.put("endAt", endAt.toString());
    body.put("sealed", sealed);
    body.put("antiSnipingSeconds", antiSnipingSeconds);
    body.put("maxExtensions", 1);
    body.put("lots", List.of(
        Map.of(
            "itemId", setup.itemId(),
            "qty", new BigDecimal("1000"),
            "uom", "EA",
            "startPrice", new BigDecimal("100"),
            "reservePrice", new BigDecimal("90"),
            "minDecrement", new BigDecimal("0.5"))));
    body.put("supplierIds", List.of(setup.supplierId()));
    return performOk(authed(json(post("/api/sourcing/auctions"), body), adminToken));
  }

  private JsonNode startAuction(String adminToken, long auctionId) throws Exception {
    return performOk(
        authed(
            json(post("/api/sourcing/auctions/" + auctionId + "/start"), Map.of("version", 0)),
            adminToken));
  }

  private JsonNode bid(
      String token, long auctionId, long lotId, String amount, String idempotencyKey)
      throws Exception {
    return performOk(
        authed(
            json(
                post("/api/supplier-portal/auctions/" + auctionId + "/lots/" + lotId + "/bids"),
                Map.of("amount", new BigDecimal(amount), "idempotencyKey", idempotencyKey)),
            token));
  }

  @Test
  @DisplayName("门户登录、出价、最小降幅、幂等、密封与防狙击")
  void portalAuthAndBidding() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Setup setup = setup(true);
    assertThat(setup.portalToken()).isNotBlank();

    JsonNode auction =
        createAuction(admin, setup, false, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10), 60);
    long auctionId = auction.path("id").asLong();
    JsonNode started = startAuction(admin, auctionId);
    assertThat(started.path("status").asText()).isEqualTo("LIVE");
    long lotId = started.path("lots").get(0).path("id").asLong();

    JsonNode streamToken =
        performOk(
            authed(
                post("/api/supplier-portal/auctions/" + auctionId + "/stream-token"),
                setup.portalToken()));
    assertThat(streamToken.path("token").asText()).isNotBlank();
    mockMvc
        .perform(
            get("/api/supplier-portal/auctions/" + auctionId + "/stream")
                .param("token", "invalid-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4008));

    JsonNode first = bid(setup.portalToken(), auctionId, lotId, "100", "K-1");
    assertThat(first.path("status").asText()).isEqualTo("VALID");
    assertThat(first.path("rank").asInt()).isEqualTo(1);
    assertThat(first.path("lowestAmount").decimalValue()).isEqualByComparingTo("100.0000");
    assertThat(first.path("nextMinAmount").decimalValue()).isEqualByComparingTo("99.5000");

    JsonNode liveState =
        performOk(
            authed(
                get("/api/supplier-portal/auctions/" + auctionId + "/state")
                    .param("lotId", String.valueOf(lotId)),
                setup.portalToken()));
    assertThat(liveState.path("lowestAmount").decimalValue()).isEqualByComparingTo("100.0000");
    assertThat(liveState.path("nextMinAmount").decimalValue()).isEqualByComparingTo("99.5000");
    assertThat(liveState.path("myRank").asInt()).isEqualTo(1);
    assertThat(liveState.path("participantCount").asLong()).isEqualTo(1);

    // 最小降幅：当前 100，最小降幅 0.5 → 99.4 有效、99.3 超限
    JsonNode lower = bid(setup.portalToken(), auctionId, lotId, "99.4", "K-2");
    assertThat(lower.path("lowestAmount").decimalValue()).isEqualByComparingTo("99.4000");
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/supplier-portal/auctions/" + auctionId + "/lots/" + lotId + "/bids"),
                    Map.of("amount", new BigDecimal("99.3"), "idempotencyKey", "K-3")),
                setup.portalToken()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2239));

    // 同键同内容幂等；同键不同内容 2247
    JsonNode replay = bid(setup.portalToken(), auctionId, lotId, "99.4", "K-2");
    assertThat(replay.path("bidId").asLong()).isEqualTo(lower.path("bidId").asLong());
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/supplier-portal/auctions/" + auctionId + "/lots/" + lotId + "/bids"),
                    Map.of("amount", new BigDecimal("99.0"), "idempotencyKey", "K-2")),
                setup.portalToken()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2247));

    // 密封竞价：不返回排名/最低价
    JsonNode sealedAuction =
        createAuction(admin, setup, true, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10), 60);
    long sealedId = sealedAuction.path("id").asLong();
    JsonNode sealedStarted = startAuction(admin, sealedId);
    long sealedLotId = sealedStarted.path("lots").get(0).path("id").asLong();
    JsonNode sealedBid = bid(setup.portalToken(), sealedId, sealedLotId, "100", "S-1");
    assertThat(sealedBid.path("sealed").asBoolean()).isTrue();
    assertThat(sealedBid.path("rank").isNull()).isTrue();
    assertThat(sealedBid.path("lowestAmount").isNull()).isTrue();
    JsonNode sealedDetail =
        performOk(authed(get("/api/supplier-portal/auctions/" + sealedId), setup.portalToken()));
    assertThat(sealedDetail.path("lots").get(0).path("currentLowestAmount").isNull()).isTrue();
    assertThat(sealedDetail.path("lots").get(0).path("myLastAmount").decimalValue())
        .isEqualByComparingTo("100.0000");
    JsonNode sealedState =
        performOk(
            authed(
                get("/api/supplier-portal/auctions/" + sealedId + "/state")
                    .param("lotId", String.valueOf(sealedLotId)),
                setup.portalToken()));
    assertThat(sealedState.path("sealed").asBoolean()).isTrue();
    assertThat(sealedState.path("lowestAmount").isNull()).isTrue();
    assertThat(sealedState.path("myRank").isNull()).isTrue();
    assertThat(sealedState.path("myLastAmount").decimalValue()).isEqualByComparingTo("100.0000");

    // 关闭后结果：自己的排名/成交价可见，密封模式也不暴露对手
    performOk(
        authed(
            json(
                post("/api/sourcing/auctions/" + auctionId + "/close"),
                Map.of("version", started.path("version").asInt())),
            admin));
    JsonNode result =
        performOk(
            authed(
                get("/api/supplier-portal/auctions/" + auctionId + "/result"),
                setup.portalToken()));
    assertThat(result.path("results").get(0).path("winner").asBoolean()).isTrue();
    assertThat(result.path("results").get(0).path("myRank").asInt()).isEqualTo(1);
    assertThat(result.path("results").get(0).path("winningAmount").decimalValue())
        .isEqualByComparingTo("99.4000");
    JsonNode closedState =
        performOk(
            authed(
                get("/api/supplier-portal/auctions/" + auctionId + "/state")
                    .param("lotId", String.valueOf(lotId)),
                setup.portalToken()));
    assertThat(closedState.path("remainingMs").asLong()).isZero();

    // 竞价结果：生成 → 创建人不能审批 → 他人审批 → 转 PO
    JsonNode results =
        performOk(
            authed(
                post("/api/sourcing/auctions/" + auctionId + "/award"), admin));
    assertThat(results).hasSize(1);
    assertThat(results.get(0).path("status").asText()).isEqualTo("DRAFT");
    assertThat(results.get(0).path("supplierId").asLong()).isEqualTo(setup.supplierId());
    assertThat(results.get(0).path("amount").decimalValue()).isEqualByComparingTo("99.4000");
    long resultId = results.get(0).path("id").asLong();
    mockMvc
        .perform(
            authed(
                post("/api/sourcing/auction-results/" + resultId + "/approve"), admin))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(4004));
    String approver = loginAs(admin, "pa-approver", Role.ADMIN);
    JsonNode approvedResult =
        performOk(
            authed(
                post("/api/sourcing/auction-results/" + resultId + "/approve"), approver));
    assertThat(approvedResult.path("status").asText()).isEqualTo("APPROVED");
    JsonNode po =
        performOk(
            authed(
                json(
                    post("/api/sourcing/auction-results/" + resultId + "/to-po"),
                    Map.of("plantId", setup.plantId())),
                approver));
    assertThat(po.path("supplierId").asLong()).isEqualTo(setup.supplierId());
    assertThat(po.path("lines").get(0).path("price").decimalValue())
        .isEqualByComparingTo("99.4000");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT source_auction_id FROM purchase_order WHERE id = ?",
                Long.class,
                po.path("id").asLong()))
        .isEqualTo(auctionId);
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/sourcing/auction-results/" + resultId + "/to-po"),
                    Map.of("plantId", setup.plantId())),
                approver))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2240));

    // 防狙击：结束前 60 秒内出价延长 endAt
    JsonNode shortAuction =
        createAuction(admin, setup, false, LocalDateTime.now(ZoneOffset.UTC).plusSeconds(30), 60);
    long shortId = shortAuction.path("id").asLong();
    JsonNode shortStarted = startAuction(admin, shortId);
    long shortLotId = shortStarted.path("lots").get(0).path("id").asLong();
    JsonNode extended = bid(setup.portalToken(), shortId, shortLotId, "100", "E-1");
    assertThat(extended.path("extended").asBoolean()).isTrue();
    assertThat(extended.path("extensionCount").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName("门户隔离：未邀请 2251、内部令牌不能访问门户、错误口令 1002、重复登录名 2253")
  void portalIsolationAndGuards() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Setup setup = setup(true);
    JsonNode auction =
        createAuction(admin, setup, false, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10), 60);
    long auctionId = auction.path("id").asLong();
    startAuction(admin, auctionId);

    // 内部 JWT 不能访问门户竞价
    mockMvc
        .perform(authed(get("/api/supplier-portal/auctions"), admin))
        .andExpect(status().isUnauthorized());

    // 未邀请的另一个供应商门户账号看到 404/2251
    long otherSupplier = createSupplier(admin, "PA-SUP-OTHER");
    createPortalUser(admin, otherSupplier, "portal-other");
    JsonNode otherLogin = portalLogin("portal-other", DEFAULT_PASSWORD);
    mockMvc
        .perform(
            authed(
                get("/api/supplier-portal/auctions/" + auctionId),
                otherLogin.path("accessToken").asText()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2251));

    // 错误口令统一 1002；重复登录名 2253
    mockMvc
        .perform(
            json(
                post("/api/supplier-portal/auth/login"),
                Map.of("loginName", "portal-buyer", "password", "WrongPassword1")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1002));
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/suppliers/" + setup.supplierId() + "/portal-user"),
                    Map.of("loginName", "portal-buyer", "password", DEFAULT_PASSWORD)),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2253));

    // 门户令牌不能访问内部接口（内部过滤器要求 uid 对应真实用户）
    mockMvc
        .perform(authed(get("/api/suppliers"), setup.portalToken()))
        .andExpect(status().isUnauthorized());

    // 刷新令牌可换新 access
    JsonNode refreshed =
        performOk(
            mockMvc.perform(
                json(
                    post("/api/supplier-portal/auth/refresh"),
                    Map.of("refreshToken", setup.refreshToken()))));
    assertThat(refreshed.path("accessToken").asText()).isNotBlank();

    // 轮换后的旧刷新令牌必须立即失效（重放不得再换出一对令牌）
    mockMvc
        .perform(
            json(
                post("/api/supplier-portal/auth/refresh"),
                Map.of("refreshToken", setup.refreshToken())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(1001));
  }

  @Test
  @DisplayName("门户出价频控：同一账号同一竞价超过窗口上限返回 1004/429")
  void rateLimitsBids() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Setup setup = setup(true);
    JsonNode auction =
        createAuction(admin, setup, false, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10), 60);
    long auctionId = auction.path("id").asLong();
    JsonNode started = startAuction(admin, auctionId);
    long lotId = started.path("lots").get(0).path("id").asLong();
    for (int i = 0; i < 20; i++) {
      String amount =
          new BigDecimal("100").subtract(new BigDecimal("0.5").multiply(BigDecimal.valueOf(i))).toPlainString();
      bid(setup.portalToken(), auctionId, lotId, amount, "R-" + i);
    }
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/supplier-portal/auctions/" + auctionId + "/lots/" + lotId + "/bids"),
                    Map.of("amount", new BigDecimal("90.0"), "idempotencyKey", "R-20")),
                setup.portalToken()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(1004));
  }

  @Test
  @DisplayName("内部竞价列表按公司与状态分页")
  void internalAuctionList() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    Setup setup = setup(false);
    createAuction(
        admin, setup, false, LocalDateTime.now(ZoneOffset.UTC).plusHours(1), 60);

    JsonNode all =
        performOk(
            authed(
                get("/api/sourcing/auctions?companyId=" + setup.companyId() + "&page=1&size=10"),
                admin));
    assertThat(all.path("total").asLong()).isEqualTo(1);
    assertThat(all.path("list").get(0).path("status").asText()).isEqualTo("DRAFT");

    JsonNode live =
        performOk(
            authed(
                get(
                    "/api/sourcing/auctions?companyId="
                        + setup.companyId()
                        + "&status=LIVE&page=1&size=10"),
                admin));
    assertThat(live.path("total").asLong()).isZero();
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "PA 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
            token));
    return idOf("company", code);
  }

  private long createPlant(String token, long companyId, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/plants"),
                Map.of("companyId", companyId, "code", code, "name", "PA 工厂 " + code)),
            token));
    return idOf("plant", code);
  }

  private String loginAs(String adminToken, String username, Role role) throws Exception {
    performOk(
        authed(
            json(
                post("/api/users"),
                Map.of(
                    "username", username,
                    "password", DEFAULT_PASSWORD,
                    "name", "PA 用例账号",
                    "role", role.name(),
                    "department", "PURCHASE")),
            adminToken));
    return login(username, DEFAULT_PASSWORD);
  }

  private long createItem(String token, String code) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("name", "PA 物料 " + code);
    body.put("itemType", "RAW");
    body.put("baseUom", "EA");
    body.put("valuationMethod", "AVCO");
    performOk(authed(json(post("/api/masterdata/items"), body), token));
    return idOf("item", code);
  }

  private long createSupplier(String token, String code) throws Exception {
    performOk(
        authed(
            json(post("/api/suppliers"), Map.of("code", code, "name", "PA 供应商 " + code, "category", "电子")),
            token));
    return idOf("supplier", code);
  }

  private long idOf(String table, String code) {
    Long id = jdbcTemplate.queryForObject("SELECT id FROM " + table + " WHERE code = ?", Long.class, code);
    if (id == null) {
      throw new IllegalStateException(table + " 不存在：" + code);
    }
    return id;
  }

  private JsonNode performOk(AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return performOk(mockMvc.perform(request));
  }

  private JsonNode performOk(ResultActions actions) throws Exception {
    MvcResult result =
        actions
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
  }
}
