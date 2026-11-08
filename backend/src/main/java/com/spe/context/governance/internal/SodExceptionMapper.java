package com.spe.context.governance.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** SoD 例外 Mapper。 */
@Mapper
public interface SodExceptionMapper extends BaseMapper<SodException> {

  String COLUMNS =
      "id, user_id, rule_id, reason, valid_from, valid_to, status, requested_by, approved_by,"
          + " approved_at, created_at, version";

  @Select("SELECT " + COLUMNS + " FROM sod_exception WHERE id = #{id}")
  Optional<SodException> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM sod_exception WHERE id = #{id} FOR UPDATE")
  Optional<SodException> lockById(@Param("id") long id);

  @Select(
      "SELECT " + COLUMNS + " FROM sod_exception ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
  List<SodException> findPage(@Param("offset") long offset, @Param("size") int size);

  @Select("SELECT COUNT(*) FROM sod_exception")
  long countAll();

  @Select(
      """
      SELECT COUNT(*) FROM sod_exception
      WHERE user_id = #{userId} AND rule_id = #{ruleId} AND status = 'APPROVED'
        AND valid_from <= #{date} AND valid_to >= #{date}
      """)
  long countApproved(
      @Param("userId") long userId,
      @Param("ruleId") long ruleId,
      @Param("date") LocalDate date);

  @Update(
      """
      UPDATE sod_exception
      SET status = #{target}, approved_by = #{operator}, approved_at = #{now},
          version = version + 1
      WHERE id = #{id} AND status = 'PENDING'
      """)
  int markReviewed(
      @Param("id") long id,
      @Param("target") SodExceptionStatus target,
      @Param("operator") long operator,
      @Param("now") LocalDateTime now);
}
