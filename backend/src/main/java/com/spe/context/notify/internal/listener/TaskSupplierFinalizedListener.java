package com.spe.context.notify.internal.listener;

import com.spe.context.evaluation.api.events.TaskSupplierFinalizedEvent;
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
 * 评估单定稿生效 → 通知采购经理（05 §5.3）。
 *
 * <p>收件人口径：01 §7 里「采购经理」就是 `MANAGER` 角色，不按部门区分；事件载荷没有任务创建人，
 * 因此按角色查全部在职经理（{@link UserQueryFacade#findEnabledIdsByRoles}）。没有任何在职经理时
 * 没有可通知的人——跳过并留一条日志，不写"收件人为空"的通知。
 *
 * <p>alert 对本事件的消费属 M5（预警），P4 只接 notify（15 §E7、11 §9）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskSupplierFinalizedListener {

  private final NotificationService notificationService;
  private final UserQueryFacade userQueryFacade;

  @EventListener
  public void onTaskSupplierFinalized(TaskSupplierFinalizedEvent event) {
    List<Long> managers = userQueryFacade.findEnabledIdsByRoles(List.of(Role.MANAGER));
    if (managers.isEmpty()) {
      log.warn("评估单 {} 已生效，但没有在职采购经理可通知", event.taskSupplierId());
      return;
    }
    String content =
        "供应商 #"
            + event.supplierId()
            + " 的 "
            + event.year()
            + " 年 Q"
            + event.quarter()
            + " 评估已生效：总分 "
            + event.totalScore().toPlainString()
            + "，等级 "
            + event.totalGrade();
    List<Notification> notifications = new ArrayList<>(managers.size());
    for (Long receiverId : managers) {
      notifications.add(
          NotificationService.compose(
              receiverId, NotificationService.TYPE_TASK_SUPPLIER_FINALIZED, "评估已生效", content));
    }
    notificationService.write(notifications);
  }
}
