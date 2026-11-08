package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** NCR Mapper。 */
@Mapper
public interface NcrMapper extends BaseMapper<Ncr> {

  String COLUMNS =
      "id, ncr_no, company_id, source_type, source_id, inspection_id, item_id, supplier_id,"
          + " lot_no, qty, defect, status, disposition, reason, created_by, created_at,"
          + " dispositioned_at, closed_at";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM ncr " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<Ncr> findPage(
      @Param("companyId") Long companyId,
      @Param("status") NcrStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM ncr " + SEARCH_CONDITION + "</script>")
  long countBy(@Param("companyId") Long companyId, @Param("status") NcrStatus status);

  @Select("SELECT " + COLUMNS + " FROM ncr WHERE id = #{id}")
  Optional<Ncr> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM ncr WHERE id = #{id} FOR UPDATE")
  Optional<Ncr> lockById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM ncr WHERE inspection_id = #{inspectionId} LIMIT 1")
  Optional<Ncr> findByInspectionId(@Param("inspectionId") long inspectionId);

  @Update(
      """
      UPDATE ncr
      SET status = 'DISPOSITIONED', disposition = #{disposition}, reason = #{reason},
          dispositioned_at = #{now}
      WHERE id = #{id} AND status = 'OPEN'
      """)
  int markDispositioned(
      @Param("id") long id,
      @Param("disposition") NcrDisposition disposition,
      @Param("reason") String reason,
      @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE ncr SET status = 'CLOSED', closed_at = #{now}
      WHERE id = #{id} AND status = 'DISPOSITIONED'
      """)
  int markClosed(@Param("id") long id, @Param("now") LocalDateTime now);
}
