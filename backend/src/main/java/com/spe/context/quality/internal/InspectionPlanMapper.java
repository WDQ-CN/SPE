package com.spe.context.quality.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 检验计划 Mapper。 */
@Mapper
public interface InspectionPlanMapper extends BaseMapper<InspectionPlan> {

  String COLUMNS =
      "id, plan_no, company_id, item_id, supplier_id, inspection_type, version, status,"
          + " sampling_plan, aql, created_by, created_at, activated_at";

  @Select("SELECT " + COLUMNS + " FROM inspection_plan WHERE id = #{id}")
  Optional<InspectionPlan> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM inspection_plan WHERE id = #{id} FOR UPDATE")
  Optional<InspectionPlan> lockById(@Param("id") long id);

  @Select(
      "SELECT " + COLUMNS + " FROM inspection_plan WHERE company_id = #{companyId}"
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
  List<InspectionPlan> findPage(
      @Param("companyId") long companyId,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("SELECT COUNT(*) FROM inspection_plan WHERE company_id = #{companyId}")
  long countBy(@Param("companyId") long companyId);

  @Update(
      """
      UPDATE inspection_plan SET status = 'ACTIVE', activated_at = #{now}
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markActive(@Param("id") long id, @Param("now") LocalDateTime now);
}
