package com.spe.context.integration.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 投递 Mapper。 */
@Mapper
public interface WebhookDeliveryMapper extends BaseMapper<WebhookDelivery> {

  String COLUMNS =
      "id, outbox_id, subscription_id, status, attempts, response_status, last_error,"
          + " created_at, delivered_at";

  @Select(
      "SELECT " + COLUMNS + " FROM webhook_delivery WHERE outbox_id = #{outboxId} ORDER BY id")
  List<WebhookDelivery> findByOutbox(@Param("outboxId") long outboxId);

  @Select(
      "SELECT " + COLUMNS + " FROM webhook_delivery"
          + " WHERE outbox_id = #{outboxId} AND subscription_id = #{subscriptionId}")
  Optional<WebhookDelivery> findByOutboxAndSubscription(
      @Param("outboxId") long outboxId, @Param("subscriptionId") long subscriptionId);

  @Update(
      """
      UPDATE webhook_delivery
      SET status = 'PENDING', response_status = NULL, last_error = NULL
      WHERE id = #{id}
      """)
  int resetForRetry(@Param("id") long id);

  @Update(
      """
      UPDATE webhook_delivery
      SET status = 'SENT', attempts = attempts + 1, response_status = #{responseStatus},
          delivered_at = #{now}, last_error = NULL
      WHERE id = #{id}
      """)
  int markSent(
      @Param("id") long id,
      @Param("responseStatus") int responseStatus,
      @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE webhook_delivery
      SET status = 'FAILED', attempts = attempts + 1, response_status = #{responseStatus},
          last_error = #{error}
      WHERE id = #{id}
      """)
  int markFailed(
      @Param("id") long id,
      @Param("responseStatus") Integer responseStatus,
      @Param("error") String error);
}
