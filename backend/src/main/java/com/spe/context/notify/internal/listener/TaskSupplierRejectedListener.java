package com.spe.context.notify.internal.listener;

import com.spe.context.evaluation.api.events.TaskSupplierRejectedEvent;
import com.spe.context.notify.internal.Notification;
import com.spe.context.notify.internal.NotificationService;
import com.spe.context.system.api.AuditDetails;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 评估单被驳回 → 通知涉及的评估人（05 §5.3）。
 *
 * <p>意见是用户输入，写进通知前按 06 §2.8 的 200 字上限截断（与审计 detail 共用
 * {@link AuditDetails#commentSummary} 这一个实现点）。
 */
@Component
@RequiredArgsConstructor
public class TaskSupplierRejectedListener {

  private final NotificationService notificationService;

  @EventListener
  public void onTaskSupplierRejected(TaskSupplierRejectedEvent event) {
    // 去重：同一人可能被指派了本评估单的多张评分单，但"被驳回"这件事只需通知一次
    List<Long> receivers = event.evaluatorIds().stream().distinct().toList();
    if (receivers.isEmpty()) {
      return;
    }
    String summary = AuditDetails.commentSummary(event.comment());
    String content =
        "评估单 #" + event.taskSupplierId() + " 被驳回，请修改后重新提交"
            + (summary.isEmpty() ? "" : "。审批意见：" + summary);
    List<Notification> notifications = new ArrayList<>(receivers.size());
    for (Long receiverId : receivers) {
      Notification notification = new Notification();
      notification.setReceiverId(receiverId);
      notification.setType(NotificationService.TYPE_TASK_SUPPLIER_REJECTED);
      notification.setTitle("评估单被驳回");
      notification.setContent(content);
      notification.setIsRead(false);
      notifications.add(notification);
    }
    notificationService.write(notifications);
  }
}
