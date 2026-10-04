package com.spe.context.notify.internal.listener;

import com.spe.context.evaluation.api.events.TaskPublishedEvent;
import com.spe.context.notify.internal.Notification;
import com.spe.context.notify.internal.NotificationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 发布任务的监听器（05 §5.3 事件契约：`TaskPublishedEvent` → notify 通知被指派评估人；13 §E16）。
 *
 * <p><b>为什么不反向依赖</b>：发布方（evaluation）**不知道 notify 存在**，只发事件；本类反向挂接。
 * 这是 05 ADR-9 的解法——定稿/发布等动作的副作用由监听方各自落库，发布方不编排下游。
 *
 * <p><b>同步语义</b>：监听器与方法在**同一个事务**里执行（W1.4 已实测证明：回滚时通知也回滚、
 * 监听器抛异常会让发布整体回滚）。所以这里**不需要** try/catch 吞异常——吞掉会违背"失败全回滚"。
 *
 * <p><b>写法约束</b>：必须用 `@EventListener` 方法，而不是 `ApplicationListener<TaskPublishedEvent>`——
 * 后者的类型参数有 `extends ApplicationEvent` 上界，而本项目的领域事件是普通 record，写了编译不过（04-086）。
 */
@Component
@RequiredArgsConstructor
public class TaskPublishedListener {

  private final NotificationService notificationService;

  @EventListener
  public void onTaskPublished(TaskPublishedEvent event) {
    // 按"人"聚合：一个人可能被指派了多张评分单（多个维度），给他写一条就够了（13 §E16）
    Map<Long, Integer> sheetCountByAssignee = new LinkedHashMap<>();
    for (TaskPublishedEvent.SheetAssignment assignment : event.assignments()) {
      if (assignment.assigneeId() == null) {
        // 待指派（建议部门下没人）⇒ 没有人可通知。不写"收件人为空"的通知（13 §E12）
        continue;
      }
      sheetCountByAssignee.merge(assignment.assigneeId(), 1, Integer::sum);
    }
    if (sheetCountByAssignee.isEmpty()) {
      return;
    }
    List<Notification> notifications = new ArrayList<>(sheetCountByAssignee.size());
    for (Map.Entry<Long, Integer> entry : sheetCountByAssignee.entrySet()) {
      Notification notification = new Notification();
      notification.setReceiverId(entry.getKey());
      notification.setType(NotificationService.TYPE_TASK_PUBLISHED);
      notification.setTitle("新的评估任务");
      notification.setContent(
          "任务《" + event.taskName() + "》（" + event.year() + " 年 Q" + event.quarter()
              + "）已发布，你被指派了 " + entry.getValue() + " 张评分单");
      notification.setIsRead(false);
      notifications.add(notification);
    }
    notificationService.write(notifications);
  }
}
