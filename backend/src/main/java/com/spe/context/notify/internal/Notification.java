package com.spe.context.notify.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 站内通知实体（V12__notification.sql / 02 §3.2 / 13 §E16 的最小版）。
 *
 * <p>生产来源：P3 的 `TaskPublishedListener`（发布任务 → 被指派评估人）与 P4-W2 的四个审批链监听器
 * （轮次开始 / 节点通过 / 驳回 / 定稿生效）。通知创建后不可修改（没有编辑入口），所以只有
 * `created_at` 没有 `updated_at`。
 */
@Data
@TableName("notification")
public class Notification {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long receiverId;

  /** 类型常量（只增不改）：P3 `TASK_PUBLISHED` + P4 四个审批链类型，见 `NotificationService`。 */
  private String type;

  private String title;

  private String content;

  /** 0 未读 / 1 已读；最小版只要求未读角标与"全部已读"（13 §E16）。 */
  private Boolean isRead;

  private LocalDateTime createdAt;
}
