package com.spe.context.notify.internal.listener;

import com.spe.context.alert.api.events.AlertCreatedEvent;
import com.spe.context.notify.internal.Notification;
import com.spe.context.notify.internal.NotificationService;
import com.spe.context.system.api.Role;
import com.spe.context.system.api.UserQueryFacade;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 预警生成 → 通知在职采购经理（01 W2；M5 W2，22 §2 D4）。
 *
 * <p>收件人口径与定稿通知一致（01 §7 的「采购经理」= MANAGER 角色，不按部门区分）。
 * 与定稿/重算同一事务：写通知失败会让整笔回滚。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertCreatedListener {

  private final NotificationService notificationService;
  private final UserQueryFacade userQueryFacade;

  @EventListener
  public void onAlertCreated(AlertCreatedEvent event) {
    List<Long> managers = userQueryFacade.findEnabledIdsByRoles(List.of(Role.MANAGER));
    if (managers.isEmpty()) {
      log.warn("预警 {} 已生成，但没有在职采购经理可通知", event.alertId());
      return;
    }
    List<Notification> notifications = new ArrayList<>(managers.size());
    for (Long receiverId : managers) {
      Notification notification = new Notification();
      notification.setReceiverId(receiverId);
      notification.setType(NotificationService.TYPE_ALERT_CREATED);
      notification.setTitle(event.title());
      notification.setContent(event.content());
      notification.setIsRead(false);
      notifications.add(notification);
    }
    notificationService.write(notifications);
  }
}
