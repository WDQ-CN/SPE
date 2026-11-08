package com.spe.context.integration.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import com.spe.common.web.BizException;
import com.spe.common.web.ErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 事务性 outbox：业务事务内写事件，派发独立执行（docs/erp/40 §3）。 */
@Service
@RequiredArgsConstructor
public class OutboxService {

  private final OutboxEventMapper outboxMapper;
  private final WebhookDeliveryMapper deliveryMapper;
  private final ObjectMapper objectMapper;

  @Transactional
  public long append(String eventType, Long aggregateId, Object payload) {
    OutboxEvent event = new OutboxEvent();
    event.setEventType(eventType);
    event.setAggregateId(aggregateId);
    try {
      event.setPayload(objectMapper.writeValueAsString(payload));
    } catch (Exception ex) {
      throw new BizException(ErrorCode.WEBHOOK_SUBSCRIPTION_INVALID, "事件序列化失败");
    }
    event.setStatus(OutboxStatus.PENDING);
    event.setAttempts(0);
    event.setTraceId(UUID.randomUUID().toString().replace("-", ""));
    outboxMapper.insert(event);
    return event.getId();
  }

  @Transactional(readOnly = true)
  public PageResult<OutboxEvent> page(int page, int size, String status) {
    PageQuery query = PageQuery.of(page, size);
    OutboxStatus parsed = parseStatus(status);
    return PageResult.of(
        outboxMapper.findPage(parsed, query.offset(), query.size()),
        outboxMapper.countBy(parsed),
        query.page(),
        query.size());
  }

  @Transactional(readOnly = true)
  public List<WebhookDelivery> deliveries(long outboxId) {
    outboxMapper
        .findById(outboxId)
        .orElseThrow(() -> new BizException(ErrorCode.OUTBOX_EVENT_NOT_FOUND));
    return deliveryMapper.findByOutbox(outboxId);
  }

  private static OutboxStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return OutboxStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new BizException(ErrorCode.BAD_REQUEST, "outbox 状态不合法：" + status);
    }
  }
}
