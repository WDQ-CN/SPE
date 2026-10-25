package com.spe.context.notify.internal.listener;

import com.spe.context.evaluation.api.events.ApprovalNodePassedEvent;
import com.spe.context.notify.internal.Notification;
import com.spe.context.notify.internal.NotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 审批节点通过 → 通知下一级审批人（05 §5.3；非末级通过由 T5 发布）。 */
@Component
@RequiredArgsConstructor
public class ApprovalNodePassedListener {

  private final NotificationService notificationService;

  @EventListener
  public void onApprovalNodePassed(ApprovalNodePassedEvent event) {
    Notification notification = new Notification();
    notification.setReceiverId(event.nextApproverId());
    notification.setType(NotificationService.TYPE_APPROVAL_NODE_PASSED);
    notification.setTitle("审批已流转");
    notification.setContent("评估单 #" + event.taskSupplierId() + " 已通过上一级审批，等待你审批");
    notification.setIsRead(false);
    notificationService.write(List.of(notification));
  }
}
