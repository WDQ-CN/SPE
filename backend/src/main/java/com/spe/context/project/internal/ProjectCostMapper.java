package com.spe.context.project.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 项目成本 Mapper。 */
@Mapper
public interface ProjectCostMapper extends BaseMapper<ProjectCost> {

  String COLUMNS =
      "id, project_id, source_type, source_id, source_line_id, amount, currency, posted_at";

  @Select(
      "SELECT COALESCE(SUM(amount), 0) FROM project_cost WHERE project_id = #{projectId}")
  BigDecimal sumByProject(@Param("projectId") long projectId);

  @Select(
      "SELECT " + COLUMNS + " FROM project_cost WHERE project_id = #{projectId}"
          + " ORDER BY id DESC")
  List<ProjectCost> findByProject(@Param("projectId") long projectId);
}
