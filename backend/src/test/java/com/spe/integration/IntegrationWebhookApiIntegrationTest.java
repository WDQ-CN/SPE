package com.spe.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.spe.context.integration.internal.OutboxService;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** E6 集成：outbox、Webhook 订阅、HMAC 签名、重试与禁用（docs/erp/40）。 */
@Transactional
class IntegrationWebhookApiIntegrationTest extends AbstractIntegrationTest {

  @Autowired private OutboxService outboxService;

  @Test
  @DisplayName("outbox 事件经 HMAC 签名投递；失败重试；禁用后不投递")
  void webhookDispatch() throws Exception {
    String admin = login(ADMIN_USERNAME, ADMIN_PASSWORD);
    long companyId = createCompany(admin, "INT-CO");

    // 云元数据地址始终拒绝（即使测试配置允许私网目标）
    mockMvc
        .perform(
            authed(
                json(
                    post("/api/integrations/subscriptions"),
                    Map.of(
                        "companyId", companyId,
                        "url", "http://169.254.169.254/latest/meta-data",
                        "eventTypes", List.of("TEST_EVENT"))),
                admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(2273));

    AtomicReference<String> body = new AtomicReference<>();
    AtomicReference<String> signature = new AtomicReference<>();
    AtomicReference<String> timestamp = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          signature.set(exchange.getRequestHeaders().getFirst("X-SPE-Signature"));
          timestamp.set(exchange.getRequestHeaders().getFirst("X-SPE-Timestamp"));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      int port = server.getAddress().getPort();
      JsonNode subscription =
          performOk(
              authed(
                  json(
                      post("/api/integrations/subscriptions"),
                      Map.of(
                          "companyId", companyId,
                          "url", "http://127.0.0.1:" + port + "/hook",
                          "eventTypes", List.of("TEST_EVENT"))),
                  admin));
      long subscriptionId = subscription.path("subscription").path("id").asLong();
      String secret = subscription.path("secret").asText();
      assertThat(secret).isNotBlank();

      long outboxId =
          outboxService.append("TEST_EVENT", 1L, Map.of("hello", "world", "amount", new BigDecimal("12.34")));
      JsonNode dispatched = performOk(authed(post("/api/integrations/outbox/dispatch"), admin));
      assertThat(dispatched.asInt()).isGreaterThanOrEqualTo(1);

      assertThat(body.get()).contains("\"hello\":\"world\"");
      assertThat(signature.get()).startsWith("sha256=");
      assertThat(signature.get())
          .isEqualTo("sha256=" + hmac(secret, timestamp.get(), body.get()));
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT status FROM outbox_event WHERE id = ?", String.class, outboxId))
          .isEqualTo("SENT");
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT status FROM webhook_delivery WHERE outbox_id = ? AND subscription_id = ?",
                  String.class,
                  outboxId,
                  subscriptionId))
          .isEqualTo("SENT");

      // 失败订阅：连接拒绝 → FAILED/重试计数
      JsonNode failSubscription =
          performOk(
              authed(
                  json(
                      post("/api/integrations/subscriptions"),
                      Map.of(
                          "companyId", companyId,
                          "url", "http://127.0.0.1:1/hook",
                          "eventTypes", List.of("FAIL_EVENT"))),
                  admin));
      long failSubscriptionId = failSubscription.path("subscription").path("id").asLong();
      long failId = outboxService.append("FAIL_EVENT", 2L, Map.of("x", 1));
      performOk(authed(post("/api/integrations/outbox/dispatch"), admin));
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT status FROM outbox_event WHERE id = ?", String.class, failId))
          .isEqualTo("FAILED");
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, failId))
          .isEqualTo(1);

      // 重试必须复用同一投递行：再次插入会撞 (outbox_id, subscription_id) 唯一键
      jdbcTemplate.update(
          "UPDATE outbox_event SET next_attempt_at = CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND"
              + " WHERE id = ?",
          failId);
      performOk(authed(post("/api/integrations/outbox/dispatch"), admin));
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, failId))
          .isEqualTo(2);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT COUNT(*) FROM webhook_delivery WHERE outbox_id = ?",
                  Long.class,
                  failId))
          .isEqualTo(1L);
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT attempts FROM webhook_delivery WHERE outbox_id = ?",
                  Integer.class,
                  failId))
          .isEqualTo(2);

      // 禁用两个订阅后，事件无订阅 → SENT 且无投递
      performOk(
          authed(post("/api/integrations/subscriptions/" + subscriptionId + "/disable"), admin));
      performOk(
          authed(
              post("/api/integrations/subscriptions/" + failSubscriptionId + "/disable"), admin));
      long noSubId = outboxService.append("TEST_EVENT", 3L, Map.of("no", "subscriber"));
      performOk(authed(post("/api/integrations/outbox/dispatch"), admin));
      assertThat(
              jdbcTemplate.queryForObject(
                  "SELECT status FROM outbox_event WHERE id = ?", String.class, noSubId))
          .isEqualTo("SENT");
    } finally {
      server.stop(0);
    }
  }

  private String hmac(String secret, String timestamp, String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  private long createCompany(String token, String code) throws Exception {
    performOk(
        authed(
            json(
                post("/api/masterdata/companies"),
                Map.of("code", code, "name", "INT 公司 " + code, "baseCurrency", "CNY", "country", "CN")),
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
