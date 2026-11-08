package com.spe.context.integration.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 事务性 outbox 事件。 */
@Data
@TableName("outbox_event")
public class OutboxEvent {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String eventType;
  private Long aggregateId;
  private String payload;
  private OutboxStatus status;
  private Integer attempts;
  private String lastError;
  private LocalDateTime nextAttemptAt;
  private String traceId;
  private LocalDateTime createdAt;
  private LocalDateTime sentAt;
}
