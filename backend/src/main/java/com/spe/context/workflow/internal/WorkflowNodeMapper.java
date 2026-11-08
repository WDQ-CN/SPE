package com.spe.context.workflow.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 工作流节点 Mapper。 */
@Mapper
public interface WorkflowNodeMapper extends BaseMapper<WorkflowNode> {

  String COLUMNS = "id, definition_id, seq_no, name, approver_role";

  @Select(
      "SELECT " + COLUMNS + " FROM workflow_node WHERE definition_id = #{definitionId}"
          + " ORDER BY seq_no")
  List<WorkflowNode> findByDefinitionId(@Param("definitionId") long definitionId);

  @Select(
      "SELECT " + COLUMNS + " FROM workflow_node"
          + " WHERE definition_id = #{definitionId} AND seq_no = #{seqNo}")
  Optional<WorkflowNode> findByDefinitionAndSeq(
      @Param("definitionId") long definitionId, @Param("seqNo") int seqNo);
}
