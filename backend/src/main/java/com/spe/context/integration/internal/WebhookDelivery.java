package com.spe.context.integration.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** Webhook 投递记录。 */
@Data
@TableName("webhook_delivery")
public class WebhookDelivery {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long outboxId;
  private Long subscriptionId;
  private DeliveryStatus status;
  private Integer attempts;
  private Integer responseStatus;
  private String lastError;
  private LocalDateTime createdAt;
  private LocalDateTime deliveredAt;
}
