package com.spe.context.integration.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import com.spe.context.system.api.AuditAction;
import com.spe.context.system.api.AuditFacade;
import com.spe.context.system.api.AuditTargetType;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Webhook 订阅（docs/erp/40 §3）。 */
@Service
@RequiredArgsConstructor
public class EventSubscriptionService {

  private static final int MIN_SECRET_LENGTH = 16;
  private static final int MAX_EVENT_TYPES_LENGTH = 500;

  private final EventSubscriptionMapper subscriptionMapper;
  private final WebhookUrlValidator urlValidator;
  private final AuditFacade auditFacade;

  @Transactional
  public Created create(
      long operatorId, long companyId, String url, List<String> eventTypes, String secret) {
    if (eventTypes == null || eventTypes.isEmpty()) {
      throw new BizException(ErrorCode.WEBHOOK_SUBSCRIPTION_INVALID, "至少订阅一个事件类型");
    }
    String normalizedUrl = url == null ? null : url.trim();
    urlValidator.validate(normalizedUrl);
    String normalizedSecret = secret == null ? null : secret.trim();
    if (normalizedSecret == null || normalizedSecret.isEmpty()) {
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      normalizedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    } else if (normalizedSecret.length() < MIN_SECRET_LENGTH) {
      throw new BizException(
          ErrorCode.WEBHOOK_SUBSCRIPTION_INVALID, "Webhook 密钥长度不得少于 " + MIN_SECRET_LENGTH);
    }
    String joinedEvents =
        eventTypes.stream()
            .map(type -> type.trim())
            .filter(type -> !type.isEmpty())
            .distinct()
            .collect(java.util.stream.Collectors.joining(","));
    if (joinedEvents.isEmpty() || joinedEvents.length() > MAX_EVENT_TYPES_LENGTH) {
      throw new BizException(ErrorCode.WEBHOOK_SUBSCRIPTION_INVALID, "订阅的事件类型为空或过多");
    }
    EventSubscription subscription = new EventSubscription();
    subscription.setCompanyId(companyId);
    subscription.setUrl(normalizedUrl);
    subscription.setSecret(normalizedSecret);
    subscription.setEventTypes(joinedEvents);
    subscription.setStatus(SubscriptionStatus.ACTIVE);
    subscription.setCreatedBy(operatorId);
    subscription.setVersion(0);
    subscriptionMapper.insert(subscription);
    auditFacade.record(
        AuditAction.INTEGRATION_SUBSCRIPTION_CREATE,
        AuditTargetType.EVENT_SUBSCRIPTION,
        subscription.getId(),
        Map.of("companyId", companyId, "url", normalizedUrl, "events", joinedEvents));
    return new Created(view(subscription), normalizedSecret);
  }

  @Transactional(readOnly = true)
  public PageResult<EventSubscriptionView> page(int page, int size, long companyId) {
    PageQuery query = PageQuery.of(page, size);
    return PageResult.of(
        subscriptionMapper.findPage(companyId, query.offset(), query.size()).stream()
            .map(EventSubscriptionService::view)
            .toList(),
        subscriptionMapper.countBy(companyId),
        query.page(),
        query.size());
  }

  @Transactional
  public EventSubscriptionView changeStatus(long id, String status) {
    EventSubscription subscription =
        subscriptionMapper
            .lockById(id)
            .orElseThrow(() -> new BizException(ErrorCode.EVENT_SUBSCRIPTION_NOT_FOUND));
    SubscriptionStatus target;
    try {
      target = SubscriptionStatus.valueOf(status.trim().toUpperCase());
    } catch (Exception ex) {
      throw new BizException(ErrorCode.WEBHOOK_SUBSCRIPTION_INVALID, "订阅状态不合法");
    }
    if (target == subscription.getStatus()) {
      throw new BizException(ErrorCode.EVENT_SUBSCRIPTION_STATUS_INVALID);
    }
    if (subscriptionMapper.updateStatus(id, target, subscription.getStatus()) == 0) {
      throw new BizException(ErrorCode.EVENT_SUBSCRIPTION_STATUS_INVALID);
    }
    auditFacade.record(
        AuditAction.INTEGRATION_SUBSCRIPTION_STATUS_CHANGE,
        AuditTargetType.EVENT_SUBSCRIPTION,
        id,
        Map.of("status", target));
    subscription.setStatus(target);
    return view(subscription);
  }

  private static EventSubscriptionView view(EventSubscription subscription) {
    return new EventSubscriptionView(
        subscription.getId(),
        subscription.getCompanyId(),
        subscription.getUrl(),
        subscription.getEventTypes(),
        subscription.getStatus(),
        subscription.getCreatedAt());
  }

  /** 创建结果：明文密钥只返回一次。 */
  public record Created(EventSubscriptionView subscription, String secret) {}
}
