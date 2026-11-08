package com.spe.context.project.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 项目预算 Mapper。 */
@Mapper
public interface ProjectBudgetMapper extends BaseMapper<ProjectBudget> {

  String COLUMNS =
      "id, project_id, version_no, labor_amount, purchase_amount, expense_amount, other_amount,"
          + " total_amount, status, created_by, created_at, approved_by, approved_at";

  @Select(
      "SELECT " + COLUMNS + " FROM project_budget WHERE project_id = #{projectId}"
          + " ORDER BY id DESC")
  List<ProjectBudget> findByProjectId(@Param("projectId") long projectId);

  @Select("SELECT " + COLUMNS + " FROM project_budget WHERE id = #{id}")
  Optional<ProjectBudget> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM project_budget WHERE id = #{id} FOR UPDATE")
  Optional<ProjectBudget> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE project_budget
      SET status = 'APPROVED', approved_by = #{operator}, approved_at = #{now}
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markApproved(
      @Param("id") long id, @Param("operator") long operator, @Param("now") LocalDateTime now);
}
