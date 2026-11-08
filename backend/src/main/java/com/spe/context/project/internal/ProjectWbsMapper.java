package com.spe.context.project.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** WBS Mapper。 */
@Mapper
public interface ProjectWbsMapper extends BaseMapper<ProjectWbs> {

  String COLUMNS = "id, project_id, parent_id, code, name, budget, status, created_at";

  @Select(
      "SELECT " + COLUMNS + " FROM project_wbs WHERE project_id = #{projectId} ORDER BY code")
  List<ProjectWbs> findByProjectId(@Param("projectId") long projectId);

  @Select("SELECT " + COLUMNS + " FROM project_wbs WHERE id = #{id} AND project_id = #{projectId}")
  Optional<ProjectWbs> findByIdAndProject(
      @Param("id") long id, @Param("projectId") long projectId);
}
