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

  /** P4 审批链（05 §5.3 的四个事件 → 本上下文）：类型名与事件名逐字一致，便于日志与排障。 */
  public static final String TYPE_APPROVAL_ROUND_STARTED = "APPROVAL_ROUND_STARTED";

  public static final String TYPE_APPROVAL_NODE_PASSED = "APPROVAL_NODE_PASSED";
  public static final String TYPE_TASK_SUPPLIER_REJECTED = "TASK_SUPPLIER_REJECTED";
  public static final String TYPE_TASK_SUPPLIER_FINALIZED = "TASK_SUPPLIER_FINALIZED";

  /** M5：预警生成（alert 事件 → 在职采购经理各一条）。 */
  public static final String TYPE_ALERT_CREATED = "ALERT_CREATED";

  /** M5：评估单被解锁重评（通知该单全部评分单指派人）。 */
  public static final String TYPE_TASK_SUPPLIER_UNLOCKED = "TASK_SUPPLIER_UNLOCKED";

  /** W4：整改任务创建/指派（通知负责人）。 */
  public static final String TYPE_CORRECTIVE_ACTION_CREATED = "CORRECTIVE_ACTION_CREATED";

  /** W4：下期验证数据已回填（通知负责人与创建人；类型名受 V12 的 VARCHAR(30) 约束）。 */
  public static final String TYPE_CORRECTIVE_VERIFY_READY = "CORRECTIVE_VERIFY_READY";

  private final NotificationMapper notificationMapper;

  /** 构造一条未读通知（唯一实现点，03 §51 P3-18）：字段口径不再散在 7 个监听器里。 */
  public static Notification compose(long receiverId, String type, String title, String content) {
    Notification notification = new Notification();
    notification.setReceiverId(receiverId);
    notification.setType(type);
    notification.setTitle(title);
    notification.setContent(content);
    notification.setIsRead(false);
    return notification;
  }

  /** 单收件人发送（内部仍走批量写，保持与监听器批量路径同一事务语义）。 */
  public int send(long receiverId, String type, String title, String content) {
    return write(List.of(compose(receiverId, type, title, content)));
  }

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
  public PageResult<NotificationSummary> page(long receiverId, PageQuery query) {
    long total = notificationMapper.countByReceiver(receiverId);
    // 投影成 Summary 再出上下文（2026-10-04）：实体是本上下文的内部形状，
    // 直接序列化会让"将来加的内部列"静默暴露（03 §34.7 的登记项）
    List<NotificationSummary> rows =
        notificationMapper.findPage(receiverId, query.offset(), query.size()).stream()
            .map(NotificationSummary::of)
            .toList();
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
