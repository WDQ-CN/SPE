package com.spe.context.evaluation.internal.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** `approval_node` 的 Mapper：发布时批处理写入，读时按 seq 升序（01 J1 的串行顺序）。 */
@Mapper
public interface ApprovalNodeMapper extends BaseMapper<ApprovalNode> {

  @Insert(
      """
      <script>
      INSERT INTO approval_node (task_id, approver_id, seq) VALUES
      <foreach item="item" collection="items" separator=",">
        (#{item.taskId}, #{item.approverId}, #{item.seq})
      </foreach>
      </script>
      """)
  int insertBatch(@Param("items") List<ApprovalNode> items);

  /** 草稿改审批链时先清空再重建（审批记录属 P4，P3 没有引用它的行）。 */
  @Delete("DELETE FROM approval_node WHERE task_id = #{taskId}")
  int deleteByTaskId(@Param("taskId") long taskId);

  @Select("SELECT id, task_id, approver_id, seq FROM approval_node WHERE task_id = #{taskId} ORDER BY seq")
  List<ApprovalNode> findByTaskId(@Param("taskId") long taskId);

  @Select("SELECT approver_id FROM approval_node WHERE task_id = #{taskId} AND seq = 1")
  Long findFirstApproverId(@Param("taskId") long taskId);
}
