package com.spe.context.project.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 项目 Mapper。 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

  String COLUMNS =
      "id, project_no, company_id, customer_id, name, currency, budget_amount,"
          + " default_labor_rate, status, start_date, end_date, manager_id, created_by,"
          + " created_at, approved_by, approved_at, closed_at, version";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM project " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<Project> findPage(
      @Param("companyId") Long companyId,
      @Param("status") ProjectStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM project " + SEARCH_CONDITION + "</script>")
  long countBy(@Param("companyId") Long companyId, @Param("status") ProjectStatus status);

  @Select("SELECT " + COLUMNS + " FROM project WHERE id = #{id}")
  Optional<Project> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM project WHERE id = #{id} FOR UPDATE")
  Optional<Project> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE project
      SET status = 'APPROVED', approved_by = #{operator}, approved_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markApproved(
      @Param("id") long id, @Param("operator") long operator, @Param("now") LocalDateTime now);

  @Update(
      "UPDATE project SET status = 'ACTIVE', version = version + 1"
          + " WHERE id = #{id} AND status = 'APPROVED'")
  int markActive(@Param("id") long id);

  @Update(
      "UPDATE project SET status = 'CLOSED', closed_at = #{now}, version = version + 1"
          + " WHERE id = #{id} AND status = 'ACTIVE'")
  int markClosed(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      "UPDATE project SET budget_amount = #{budget}, version = version + 1 WHERE id = #{id}")
  int updateBudget(@Param("id") long id, @Param("budget") BigDecimal budget);
}
