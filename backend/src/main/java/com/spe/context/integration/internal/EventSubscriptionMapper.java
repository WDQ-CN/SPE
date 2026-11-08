package com.spe.context.integration.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 订阅 Mapper。 */
@Mapper
public interface EventSubscriptionMapper extends BaseMapper<EventSubscription> {

  String COLUMNS = "id, company_id, url, secret, event_types, status, created_by, created_at, version";

  @Select("SELECT " + COLUMNS + " FROM event_subscription WHERE id = #{id}")
  Optional<EventSubscription> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM event_subscription WHERE id = #{id} FOR UPDATE")
  Optional<EventSubscription> lockById(@Param("id") long id);

  @Select(
      "SELECT " + COLUMNS + " FROM event_subscription WHERE company_id = #{companyId}"
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
  List<EventSubscription> findPage(
      @Param("companyId") long companyId,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("SELECT COUNT(*) FROM event_subscription WHERE company_id = #{companyId}")
  long countBy(@Param("companyId") long companyId);

  @Select("SELECT " + COLUMNS + " FROM event_subscription WHERE status = 'ACTIVE'")
  List<EventSubscription> findActive();

  @Update(
      """
      UPDATE event_subscription SET status = #{target}, version = version + 1
      WHERE id = #{id} AND status = #{expected}
      """)
  int updateStatus(
      @Param("id") long id,
      @Param("target") SubscriptionStatus target,
      @Param("expected") SubscriptionStatus expected);
}
