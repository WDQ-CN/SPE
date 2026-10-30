package com.spe.context.evaluation.api.events;

import com.spe.common.event.DomainEvent;
import java.util.List;

/**
 * 评估单被管理员解锁重评（01 R5 / 05 §5.5 T4，M5 W3）。
 *
 * <p>发布方：`UnlockService`（与状态回退、评分单解锁、快照删除同一事务）。
 * 消费方：alert（旧预警置 SUPERSEDED）、notify（通知该单全部评分单指派人需重评）。
 * `assigneeIds` 随载荷带出，notify 不需要反查 evaluation 的 internal Mapper。
 */
public record TaskSupplierUnlockedEvent(
    long taskSupplierId,
    long supplierId,
    int year,
    int quarter,
    int round,
    List<Long> assigneeIds)
    implements DomainEvent {}
