package com.spe.context.notify.internal.listener;

import com.spe.context.evaluation.api.events.TaskSupplierUnlockedEvent;
import com.spe.context.notify.internal.Notification;
import com.spe.context.notify.internal.NotificationService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 评估单被解锁重评 → 通知该单全部评分单指派人（01 R5；M5 W3，22 §2 D4）。
 *
 * <p>收件人由事件载荷带出（evaluation 的 `findDistinctAssigneeIds`），notify 不反查 evaluation 的
 * internal Mapper。无收件人时跳过并留日志，不写"收件人为空"的通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskSupplierUnlockedListener {

  private final NotificationService notificationService;

  @EventListener
  public void onTaskSupplierUnlocked(TaskSupplierUnlockedEvent event) {
    List<Long> receivers = event.assigneeIds();
    if (receivers == null || receivers.isEmpty()) {
      log.warn("评估单 {} 已解锁，但没有评分单指派人可通知", event.taskSupplierId());
      return;
    }
    String content =
        "评估单 #"
            + event.taskSupplierId()
            + "（"
            + event.year()
            + " 年 Q"
            + event.quarter()
            + "）已被管理员解锁重评，请重新评分后提交。";
    List<Notification> notifications = new ArrayList<>(receivers.size());
    for (Long receiverId : receivers) {
      notifications.add(
          NotificationService.compose(
              receiverId, NotificationService.TYPE_TASK_SUPPLIER_UNLOCKED, "评估单已解锁", content));
    }
    notificationService.write(notifications);
  }
}
