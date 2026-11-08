package com.spe.context.workflow.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 工作流实例 Mapper。 */
@Mapper
public interface WorkflowInstanceMapper extends BaseMapper<WorkflowInstance> {

  String COLUMNS =
      "id, instance_no, definition_id, doc_type, doc_id, company_id, amount, status,"
          + " current_seq, started_by, started_at, completed_at, version";

  String SEARCH_CONDITION =
      """
      <where>
        <if test="companyId != null">AND company_id = #{companyId}</if>
        <if test="status != null">AND status = #{status}</if>
        <if test="docType != null and docType != ''">AND doc_type = #{docType}</if>
      </where>
      """;

  @Select(
      "<script>SELECT " + COLUMNS + " FROM workflow_instance " + SEARCH_CONDITION
          + " ORDER BY id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<WorkflowInstance> findPage(
      @Param("companyId") Long companyId,
      @Param("status") WorkflowInstanceStatus status,
      @Param("docType") String docType,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select("<script>SELECT COUNT(*) FROM workflow_instance " + SEARCH_CONDITION + "</script>")
  long countBy(
      @Param("companyId") Long companyId,
      @Param("status") WorkflowInstanceStatus status,
      @Param("docType") String docType);

  @Select("SELECT " + COLUMNS + " FROM workflow_instance WHERE id = #{id}")
  Optional<WorkflowInstance> findById(@Param("id") long id);

  @Select(
      "SELECT COUNT(*) FROM workflow_instance WHERE doc_type = #{docType}"
          + " AND doc_id = #{docId} AND status = 'RUNNING'")
  long countRunningByDoc(@Param("docType") String docType, @Param("docId") long docId);

  @Select("SELECT " + COLUMNS + " FROM workflow_instance WHERE id = #{id} FOR UPDATE")
  Optional<WorkflowInstance> lockById(@Param("id") long id);

  @Update(
      "UPDATE workflow_instance SET current_seq = #{seq}, version = version + 1"
          + " WHERE id = #{id} AND status = 'RUNNING'")
  int advance(@Param("id") long id, @Param("seq") int seq);

  @Update(
      """
      UPDATE workflow_instance
      SET status = #{target}, completed_at = #{now}, version = version + 1
      WHERE id = #{id} AND status = 'RUNNING'
      """)
  int complete(
      @Param("id") long id,
      @Param("target") WorkflowInstanceStatus target,
      @Param("now") LocalDateTime now);
}
