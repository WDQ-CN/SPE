package com.spe.context.workflow.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 工作流任务 Mapper。 */
@Mapper
public interface WorkflowTaskMapper extends BaseMapper<WorkflowTask> {

  String COLUMNS =
      "id, instance_id, seq_no, approver_role, status, assigned_at, acted_by, acted_at, opinion";

  @Select(
      "SELECT " + COLUMNS + " FROM workflow_task WHERE instance_id = #{instanceId}"
          + " ORDER BY seq_no")
  List<WorkflowTask> findByInstanceId(@Param("instanceId") long instanceId);

  @Select("SELECT " + COLUMNS + " FROM workflow_task WHERE id = #{id}")
  Optional<WorkflowTask> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM workflow_task WHERE id = #{id} FOR UPDATE")
  Optional<WorkflowTask> lockById(@Param("id") long id);

  @Select(
      """
      <script>SELECT id, instance_id, seq_no, approver_role, status, assigned_at, acted_by,
                     acted_at, opinion
      FROM workflow_task WHERE status = 'PENDING'
        <if test="roles != null and roles.size() > 0">
          AND approver_role IN
          <foreach collection="roles" item="role" open="(" separator="," close=")">#{role}</foreach>
        </if>
      ORDER BY id LIMIT #{size}</script>
      """)
  List<WorkflowTask> findPendingByRoles(
      @Param("roles") List<String> roles, @Param("size") int size);

  @Select(
      "SELECT COUNT(*) FROM workflow_task WHERE instance_id = #{instanceId}"
          + " AND acted_by = #{operatorId} AND status = 'APPROVED'")
  long countApprovedByInstanceAndActor(
      @Param("instanceId") long instanceId, @Param("operatorId") long operatorId);

  @Update(
      """
      UPDATE workflow_task
      SET status = 'APPROVED', acted_by = #{operator}, acted_at = #{now}, opinion = #{opinion}
      WHERE id = #{id} AND status = 'PENDING'
      """)
  int markApproved(
      @Param("id") long id,
      @Param("operator") long operator,
      @Param("now") LocalDateTime now,
      @Param("opinion") String opinion);

  @Update(
      """
      UPDATE workflow_task
      SET status = 'REJECTED', acted_by = #{operator}, acted_at = #{now}, opinion = #{opinion}
      WHERE id = #{id} AND status = 'PENDING'
      """)
  int markRejected(
      @Param("id") long id,
      @Param("operator") long operator,
      @Param("now") LocalDateTime now,
      @Param("opinion") String opinion);
}
