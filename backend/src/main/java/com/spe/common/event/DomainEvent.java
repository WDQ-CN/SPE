package com.spe.common.event;

/**
 * 领域事件基类型（09 D8：接口，由 record 实现）——保证载荷不可变，字段全为 final（ArchUnit R-D）。
 *
 * <p>事件契约清单以 05 §5.3 为唯一来源；监听器只能消费事件载荷，不得回调发布方 internal。
 */
public interface DomainEvent {
}
