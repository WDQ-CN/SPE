package com.spe.context.workflow.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 工作流定义 Mapper。 */
@Mapper
public interface WorkflowDefinitionMapper extends BaseMapper<WorkflowDefinition> {

  String COLUMNS =
      "id, code, name, doc_type, version_no, condition_min_amount, condition_max_amount,"
          + " status, created_by, created_at, activated_at";

  @Select("SELECT " + COLUMNS + " FROM workflow_definition WHERE id = #{id}")
  Optional<WorkflowDefinition> findById(@Param("id") long id);

  @Select("SELECT " + COLUMNS + " FROM workflow_definition WHERE id = #{id} FOR UPDATE")
  Optional<WorkflowDefinition> lockById(@Param("id") long id);

  @Select(
      "SELECT " + COLUMNS + " FROM workflow_definition ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
  List<WorkflowDefinition> findPage(
      @Param("offset") long offset, @Param("size") int size);

  @Select("SELECT COUNT(*) FROM workflow_definition")
  long countAll();

  @Select(
      """
      SELECT id, code, name, doc_type, version_no, condition_min_amount, condition_max_amount,
             status, created_by, created_at, activated_at
      FROM workflow_definition
      WHERE doc_type = #{docType} AND status = 'ACTIVE'
        AND condition_min_amount <= #{amount}
        AND (condition_max_amount IS NULL OR condition_max_amount >= #{amount})
      ORDER BY id DESC LIMIT 1
      """)
  Optional<WorkflowDefinition> findMatching(
      @Param("docType") String docType, @Param("amount") BigDecimal amount);

  @Update(
      """
      UPDATE workflow_definition SET status = 'ACTIVE', activated_at = #{now}
      WHERE id = #{id} AND status = 'DRAFT'
      """)
  int markActive(@Param("id") long id, @Param("now") LocalDateTime now);

  @Update(
      "UPDATE workflow_definition SET status = 'INACTIVE'"
          + " WHERE doc_type = #{docType} AND id <> #{exceptId} AND status = 'ACTIVE'")
  int deactivateOthers(
      @Param("docType") String docType, @Param("exceptId") long exceptId);
}
