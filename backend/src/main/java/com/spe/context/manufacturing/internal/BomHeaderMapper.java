package com.spe.context.manufacturing.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** BOM 头 Mapper。 */
@Mapper
public interface BomHeaderMapper extends BaseMapper<BomHeader> {

  String COLUMNS =
      "id, bom_no, company_id, item_id, version, status, created_by, created_at, activated_at";

  @Select(
      "SELECT " + COLUMNS + " FROM bom_header WHERE id = #{id}")
  Optional<BomHeader> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM bom_header WHERE id = #{id} FOR UPDATE")
  Optional<BomHeader> lockById(@Param("id") long id);

  @Select(
      "SELECT " + COLUMNS + " FROM bom_header WHERE company_id = #{companyId}"
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
  List<BomHeader> findPage(
      @Param("companyId") long companyId,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("SELECT COUNT(*) FROM bom_header WHERE company_id = #{companyId}")
  long countBy(@Param("companyId") long companyId);

  @Update(
      """
      UPDATE bom_header SET status = 'ACTIVE', activated_at = #{now}
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markActive(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      "UPDATE bom_header SET status = 'INACTIVE'"
          + " WHERE company_id = #{companyId} AND item_id = #{itemId}"
          + " AND id <> #{exceptId} AND status = 'ACTIVE'")
  int deactivateOthers(
      @Param("companyId") long companyId,
      @Param("itemId") long itemId,
      @Param("exceptId") long exceptId);
}
