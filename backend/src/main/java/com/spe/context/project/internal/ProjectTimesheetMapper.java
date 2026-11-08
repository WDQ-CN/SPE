package com.spe.context.project.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 工时单 Mapper。 */
@Mapper
public interface ProjectTimesheetMapper extends BaseMapper<ProjectTimesheet> {

  String COLUMNS =
      "id, timesheet_no, company_id, user_id, status, total_hours, total_amount, created_at,"
          + " submitted_at, approved_by, approved_at, version";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="userId != null">AND user_id = #{userId}</if>
        <if test="status != null">AND status = #{status}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM project_timesheet " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<ProjectTimesheet> findPage(
      @Param("companyId") Long companyId,
      @Param("userId") Long userId,
      @Param("status") TimesheetStatus status,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM project_timesheet " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("userId") Long userId,
      @Param("status") TimesheetStatus status);

  @Select("SELECT " + COLUMNS + " FROM project_timesheet WHERE id = #{id}")
  Optional<ProjectTimesheet> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM project_timesheet WHERE id = #{id} FOR UPDATE")
  Optional<ProjectTimesheet> lockById(@Param("id") long id);

  @Update(
      """
      UPDATE project_timesheet SET status = 'SUBMITTED', submitted_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markSubmitted(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      """
      UPDATE project_timesheet
      SET status = 'APPROVED', approved_by = #{operator}, approved_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'SUBMITTED'
      """)
  int markApproved(
      @Param("id") long id, @Param("operator") long operator, @Param("now") LocalDateTime now);
}
