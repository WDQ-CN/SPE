package com.spe.context.integration.internal;

import com.spe.common.security.Hashes;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * outbox 派发与 Webhook 投递（docs/erp/40 §3）：2xx 成功；失败指数退避，5 次入 DEAD。
 *
 * <p>当前为手动/外部触发派发，避免在核心业务事务内同步外呼。加密密钥存库但不回显；
 * 生产应接 KMS 并配定时任务/队列。
 */
@Component
@RequiredArgsConstructor
public class WebhookDispatcher {

  private static final int MAX_ATTEMPTS = 5;

  private final OutboxEventMapper outboxMapper;
  private final EventSubscriptionMapper subscriptionMapper;
  private final WebhookDeliveryMapper deliveryMapper;
  private final WebhookUrlValidator urlValidator;
  private final AuditFacade auditFacade;
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  public int dispatch() {
    List<OutboxEvent> due =
        outboxMapper.findDue(LocalDateTime.now(ZoneOffset.UTC), 50);
    // 一次派发内订阅集合不变：原先每个事件都查一次 findActive()，事件越多越接近 N+1。
    List<EventSubscription> active = subscriptionMapper.findActive();
    int processed = 0;
    for (OutboxEvent event : due) {
      if (dispatchOne(event, active)) {
        processed++;
      }
    }
    if (processed > 0) {
      auditFacade.record(
          AuditAction.OUTBOX_DISPATCH,
          AuditTargetType.OUTBOX_EVENT,
          0L,
          Map.of("processed", processed));
    }
    return processed;
  }

  private boolean dispatchOne(OutboxEvent event, List<EventSubscription> active) {
    List<EventSubscription> subscriptions =
        active.stream()
            .filter(subscription -> subscribes(subscription, event.getEventType()))
            .toList();
    if (subscriptions.isEmpty()) {
      outboxMapper.markSent(event.getId(), LocalDateTime.now(ZoneOffset.UTC));
      return true;
    }
    boolean allOk = true;
    String lastError = null;
    for (EventSubscription subscription : subscriptions) {
      WebhookDelivery delivery = prepareDelivery(event, subscription);
      try {
        urlValidator.validate(subscription.getUrl());
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(subscription.getSecret(), timestamp, event.getPayload());
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(subscription.getUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-SPE-Event", event.getEventType())
                .header("X-SPE-Delivery", String.valueOf(event.getId()))
                .header("X-SPE-Timestamp", timestamp)
                .header("X-SPE-Signature", "sha256=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(event.getPayload(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 == 2) {
          deliveryMapper.markSent(
              delivery.getId(), response.statusCode(), LocalDateTime.now(ZoneOffset.UTC));
        } else {
          allOk = false;
          lastError = "HTTP " + response.statusCode();
          deliveryMapper.markFailed(delivery.getId(), response.statusCode(), lastError);
        }
      } catch (Exception ex) {
        allOk = false;
        lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        deliveryMapper.markFailed(delivery.getId(), null, truncate(lastError));
      }
    }
    if (allOk) {
      outboxMapper.markSent(event.getId(), LocalDateTime.now(ZoneOffset.UTC));
    } else {
      int attempts = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
      OutboxStatus status = attempts >= MAX_ATTEMPTS ? OutboxStatus.DEAD : OutboxStatus.FAILED;
      LocalDateTime next =
          LocalDateTime.now(ZoneOffset.UTC).plusSeconds(Math.min(300, 1L << Math.min(attempts, 8)));
      outboxMapper.markFailure(event.getId(), status, attempts, truncate(lastError), next);
    }
    return true;
  }

  /**
   * 每个 (outbox, subscription) 只有一行投递记录（V1031 的唯一键）。重试必须复用并重置该行，
   * 否则第二次派发会撞唯一键，整个 dispatch 直接失败。
   */
  private WebhookDelivery prepareDelivery(OutboxEvent event, EventSubscription subscription) {
    WebhookDelivery existing =
        deliveryMapper
            .findByOutboxAndSubscription(event.getId(), subscription.getId())
            .orElse(null);
    if (existing != null) {
      deliveryMapper.resetForRetry(existing.getId());
      return existing;
    }
    WebhookDelivery delivery = new WebhookDelivery();
    delivery.setOutboxId(event.getId());
    delivery.setSubscriptionId(subscription.getId());
    delivery.setStatus(DeliveryStatus.PENDING);
    delivery.setAttempts(0);
    try {
      deliveryMapper.insert(delivery);
      return delivery;
    } catch (DuplicateKeyException ex) {
      WebhookDelivery concurrent =
          deliveryMapper
              .findByOutboxAndSubscription(event.getId(), subscription.getId())
              .orElseThrow(() -> ex);
      deliveryMapper.resetForRetry(concurrent.getId());
      return concurrent;
    }
  }

  private static boolean subscribes(EventSubscription subscription, String eventType) {
    return Arrays.stream(subscription.getEventTypes().split(","))
        .map(String::trim)
        .anyMatch(type -> type.equalsIgnoreCase(eventType));
  }

  private static String sign(String secret, String timestamp, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
      return Hashes.hex(digest);
    } catch (Exception ex) {
      throw new IllegalStateException("HMAC 计算失败", ex);
    }
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() <= 500 ? value : value.substring(0, 500);
  }
}
