package com.spe.context.notify.internal;

import java.time.LocalDateTime;

/**
 * 通知的对外投影（2026-10-04 补，`03 §34.7` 的遗留项）。
 *
 * <p>在此之前 `NotificationController` **直接序列化实体**，是本项目**唯一**一处这么做的端点
 * ——其余上下文都出 Summary/DTO，形态见 `supplier.internal.SupplierSummary`。
 *
 * <p>直接出实体的代价不是"多一个字段"，而是**将来给实体加内部字段会静默暴露**：
 * `receiverId`（收件人 id）现在就在响应里，而前端的 `NotificationSummary` 本来也没有它；
 * 哪天实体加上"是否已推送""批次号"这类内部列，也会无声地跟着出去。
 *
 * <p>字段与前端 `shared/types/dto.ts` 的 `NotificationSummary` 一一对应。
 */
public record NotificationSummary(
    long id, String type, String title, String content, boolean isRead, LocalDateTime createdAt) {

  static NotificationSummary of(Notification notification) {
    return new NotificationSummary(
        notification.getId(),
        notification.getType(),
        notification.getTitle(),
        notification.getContent(),
        Boolean.TRUE.equals(notification.getIsRead()),
        notification.getCreatedAt());
  }
}
