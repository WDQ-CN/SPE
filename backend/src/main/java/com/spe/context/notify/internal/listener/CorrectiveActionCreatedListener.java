package com.spe.context.notify.internal.listener;

import com.spe.context.alert.api.events.CorrectiveActionCreatedEvent;
import com.spe.context.notify.internal.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 整改任务创建/指派 → 通知负责人（W4）。
 *
 * <p>与创建整改同一事务：写通知失败会让创建回滚（05 ADR-9）。创建人若同时是负责人，
 * 仍然只收到一条“已指派给你”的通知。
 */
@Component
@RequiredArgsConstructor
public class CorrectiveActionCreatedListener {

  private final NotificationService notificationService;

  @EventListener
  public void onCorrectiveActionCreated(CorrectiveActionCreatedEvent event) {
    String content =
        "供应商 "
            + event.supplierName()
            + " 的整改任务 #"
            + event.actionId()
            + " 已指派给你，截止 "
            + event.dueDate()
            + "。";
    notificationService.send(
        event.ownerId(), NotificationService.TYPE_CORRECTIVE_ACTION_CREATED, "整改任务已指派", content);
  }
}
