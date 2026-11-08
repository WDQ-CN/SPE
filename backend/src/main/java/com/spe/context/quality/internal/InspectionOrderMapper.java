package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 检验单 Mapper。 */
@Mapper
public interface InspectionOrderMapper extends BaseMapper<InspectionOrder> {

  String COLUMNS =
      "id, order_no, company_id, plan_id, source_type, source_id, source_line_id, item_id,"
          + " supplier_id, lot_no, qty, status, created_by, created_at, completed_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM inspection_order " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<InspectionOrder> findPage(
      @Param("companyId") Long companyId,
      @Param("status") InspectionStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM inspection_order " + SEARCH_CONDITION + "</script>")
  long countBy(@Param("companyId") Long companyId, @Param("status") InspectionStatus status);

  @Select("SELECT " + COLUMNS + " FROM inspection_order WHERE id = #{id}")
  Optional<InspectionOrder> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM inspection_order WHERE id = #{id} FOR UPDATE")
  Optional<InspectionOrder> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE inspection_order SET status = 'DEVIATED'
      WHERE id = #{id} AND status = 'FAILED'
      """)
  int markDeviated(@Param("id") long id);

  @Update(
      """
      UPDATE inspection_order
      SET status = #{target}, completed_at = #{now}
      WHERE id = #{id} AND status IN ('PENDING','IN_PROGRESS')
      """)
  int markCompleted(
      @Param("id") long id,
      @Param("target") InspectionStatus target,
      @Param("now") LocalDateTime now);
}
