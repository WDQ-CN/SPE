package com.spe.context.notify.internal.listener;

import com.spe.context.evaluation.api.events.ApprovalRoundStartedEvent;
import com.spe.context.notify.internal.Notification;
import com.spe.context.notify.internal.NotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 审批轮次开始 → 通知第一级审批人（05 §5.3；销项 P3 留下的"已发布无监听器"中间态，15 §E7）。
 *
 * <p>同步语义、写法约束与 `TaskPublishedListener` 相同：`@EventListener` 方法、同一事务、
 * 不吞异常（监听失败随提交整体回滚）。
 */
@Component
@RequiredArgsConstructor
public class ApprovalRoundStartedListener {

  private final NotificationService notificationService;

  @EventListener
  public void onApprovalRoundStarted(ApprovalRoundStartedEvent event) {
    notificationService.send(
        event.approverId(),
        NotificationService.TYPE_APPROVAL_ROUND_STARTED,
        "评估单待审批",
        "评估单 #" + event.taskSupplierId() + " 第 " + event.round() + " 轮审批已发起，等待你审批");
  }
}
