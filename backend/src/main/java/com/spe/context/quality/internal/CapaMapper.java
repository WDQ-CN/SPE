package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** CAPA Mapper。 */
@Mapper
public interface CapaMapper extends BaseMapper<Capa> {

  String COLUMNS =
      "id, capa_no, company_id, ncr_id, supplier_id, root_cause, action_plan, status,"
          + " created_by, created_at, verified_by, verified_at, closed_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM capa " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<Capa> findPage(
      @Param("companyId") Long companyId,
      @Param("status") CapaStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM capa " + SEARCH_CONDITION + "</script>")
  long countBy(@Param("companyId") Long companyId, @Param("status") CapaStatus status);

  @Select("SELECT " + COLUMNS + " FROM capa WHERE id = #{id}")
  Optional<Capa> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM capa WHERE id = #{id} FOR UPDATE")
  Optional<Capa> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE capa
      SET status = 'VERIFIED', verified_by = #{operator}, verified_at = #{now}
      WHERE id = #{id} AND status IN ('OPEN','IN_PROGRESS')
      """)
  int markVerified(
      @Param("id") long id, @Param("operator") long operator, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE capa SET status = 'CLOSED', closed_at = #{now}
      WHERE id = #{id} AND status = 'VERIFIED'
      """)
  int markClosed(@Param("id") long id, @Param("now") LocalDateTime now);
}
