package com.spe.context.notify.internal.listener;

import com.spe.context.alert.api.events.CorrectiveActionVerificationReadyEvent;
import com.spe.context.notify.internal.NotificationService;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 整改下期验证数据回填 → 通知负责人与创建人（W4）。
 *
 * <p>收件人由事件载荷带出，notify 不反查 alert 的内部表；负责人与创建人可能是同一人，用
 * `LinkedHashSet` 去重，避免同一个人收到两条相同通知。PENDING 也通知，因为经理需要人工判断。
 */
@Component
@RequiredArgsConstructor
public class CorrectiveActionVerificationReadyListener {

  private final NotificationService notificationService;

  @EventListener
  public void onCorrectiveActionVerificationReady(CorrectiveActionVerificationReadyEvent event) {
    Set<Long> receivers = new LinkedHashSet<>();
    receivers.add(event.ownerId());
    receivers.add(event.createdBy());

    String scoreText = event.verifyScore() == null ? "—" : event.verifyScore().toPlainString();
    String content =
        "供应商 "
            + event.supplierName()
            + " 的整改任务 #"
            + event.actionId()
            + " 已回填 "
            + event.verifyYear()
            + " Q"
            + event.verifyQuarter()
            + " 结果：实际分 "
            + scoreText
            + "，初步结论 "
            + outcomeLabel(event.verifyOutcome())
            + "。请验证并确认。";
    for (Long receiverId : receivers) {
      notificationService.send(
          receiverId,
          NotificationService.TYPE_CORRECTIVE_VERIFY_READY,
          "整改验证就绪",
          content);
    }
  }

  private static String outcomeLabel(String outcome) {
    if (outcome == null) {
      return "待人工判断";
    }
    return switch (outcome) {
      case "IMPROVED" -> "已改善";
      case "NOT_IMPROVED" -> "未改善";
      case "PENDING" -> "待人工判断";
      default -> outcome;
    };
  }
}
