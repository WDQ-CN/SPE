package com.spe.context.notify.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 站内通知实体（V12__notification.sql / 02 §3.2 / 13 §E16 的最小版）。
 *
 * <p>P3 只有一个生产来源：` TaskPublishedListener` 消费发布事件后给**被指派的评估人**各写一条。
 * 通知创建后不可修改（没有编辑入口），所以只有 `created_at` 没有 `updated_at`。
 */
@Data
@TableName("notification")
public class Notification {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long receiverId;

  /** 类型常量（只增不改）：P3 只有 `TASK_PUBLISHED`。 */
  private String type;

  private String title;

  private String content;

  /** 0 未读 / 1 已读；最小版只要求未读角标与"全部已读"（13 §E16）。 */
  private Boolean isRead;

  private LocalDateTime createdAt;
}
