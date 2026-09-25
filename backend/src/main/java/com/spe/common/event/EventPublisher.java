package com.spe.common.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 进程内同步领域事件发布器（09 D8 / 05 ADR-9）：监听器在发布方事务内同步执行，失败随主事务回滚。
 *
 * <p>发布方只依赖本类与事件类型，不感知监听者；未来换 MQ 时替换本实现，发布点不变。
 * 日志只记事件类型不记载荷，避免把敏感字段写进日志（06 §2.8）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPublisher {

  private final ApplicationEventPublisher delegate;

  public void publish(DomainEvent event) {
    log.info("发布领域事件 {}", event.getClass().getSimpleName());
    delegate.publishEvent(event);
  }
}
