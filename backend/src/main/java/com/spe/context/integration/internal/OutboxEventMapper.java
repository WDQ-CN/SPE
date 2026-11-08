package com.spe.context.integration.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** outbox Mapper。 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

  String COLUMNS =
      "id, event_type, aggregate_id, payload, status, attempts, last_error, next_attempt_at,"
          + " trace_id, created_at, sent_at";

  @Select(
      """
      SELECT id, event_type, aggregate_id, payload, status, attempts, last_error,
             next_attempt_at, trace_id, created_at, sent_at
      FROM outbox_event
      WHERE status IN ('PENDING','FAILED')
        AND (next_attempt_at IS NULL OR next_attempt_at <= #{now})
      ORDER BY id LIMIT #{limit}
      """)
  List<OutboxEvent> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

  @Select("SELECT " + COLUMNS + " FROM outbox_event WHERE id = #{id}")
  Optional<OutboxEvent> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM outbox_event WHERE id = #{id} FOR UPDATE")
  Optional<OutboxEvent> lockById(@Param("id") long id);

  @Select(
      """
      <script>SELECT id, event_type, aggregate_id, payload, status, attempts, last_error,
                     next_attempt_at, trace_id, created_at, sent_at
      FROM outbox_event
      <where>
        <if test="status != null">AND status = #{status}</if>
      </where>
      ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>
      """)
  List<OutboxEvent> findPage(
      @Param("status") OutboxStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      "<script>SELECT COUNT(*) FROM outbox_event"
          + "<where><if test=\"status != null\">AND status = #{status}</if></where></script>")
  long countBy(@Param("status") OutboxStatus status);

  @Update(
      "UPDATE outbox_event SET status = 'SENT', sent_at = #{now}, last_error = NULL"
          + " WHERE id = #{id}")
  int markSent(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE outbox_event
      SET status = #{status}, attempts = #{attempts}, last_error = #{error},
          next_attempt_at = #{nextAttempt}
      WHERE id = #{id}
      """)
  int markFailure(
      @Param("id") long id,
      @Param("status") OutboxStatus status,
      @Param("attempts") int attempts,
      @Param("error") String error,
      @Param("nextAttempt") LocalDateTime nextAttempt);
}
