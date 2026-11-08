package com.spe.context.integration.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 事件订阅（V1031）。 */
@Data
@TableName("event_subscription")
public class EventSubscription {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long companyId;
  private String url;
  private String secret;
  private String eventTypes;
  private SubscriptionStatus status;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Integer version;
}
