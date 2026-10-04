package com.spe.context.notify.internal;

import com.spe.common.page.PageQuery;
import com.spe.common.page.PageResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知服务（13 §E16 的最小版）：写通知、列表分页、未读数、全部已读。
 *
 * <p>`write` 是给监听器用的（发布事件 → 给被指派的人各写一条）；另外三个方法供 W5 的三个端点。
 * 全角色都只看**自己的**：receiverId 一律由调用方从认证主体传入，SQL 里再钉一次（第二层过滤）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

  /** 通知类型常量（与 V12 列注释、02 §3.2 一致；只增不改）。 */
  public static final String TYPE_TASK_PUBLISHED = "TASK_PUBLISHED";

  private final NotificationMapper notificationMapper;

  /** 批量写通知（监听器在发布事务内调用 ⇒ 通知与发布同生共死，05 ADR-9）。 */
  @Transactional
  public int write(List<Notification> notifications) {
    if (notifications.isEmpty()) {
      return 0;
    }
    int written = notificationMapper.insertBatch(notifications);
    log.info("写入站内通知 {} 条（类型 {}）", written, notifications.get(0).getType());
    return written;
  }

  @Transactional(readOnly = true)
  public PageResult<Notification> page(long receiverId, PageQuery query) {
    long total = notificationMapper.countByReceiver(receiverId);
    List<Notification> rows =
        notificationMapper.findPage(receiverId, query.offset(), query.size());
    return new PageResult<>(rows, total, query.page(), query.size());
  }

  @Transactional(readOnly = true)
  public long unreadCount(long receiverId) {
    return notificationMapper.countUnread(receiverId);
  }

  @Transactional
  public int markAllRead(long receiverId) {
    return notificationMapper.markAllRead(receiverId);
  }
}
