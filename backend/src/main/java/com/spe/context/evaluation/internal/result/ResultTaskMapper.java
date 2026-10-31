package com.spe.context.evaluation.internal.result;

import com.spe.common.sql.EffectiveResults;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 结果报表入口的任务查询（01 R2 / 02 §4 的 `GET /results/tasks`）。
 *
 * <p>⚠️ 为什么不复用 `/api/tasks`：那个端点按 01 §7 只对 ADMIN/MANAGER 开放，而结果报表
 * 对 VIEWER 也可读 ⇒ VIEWER 会拿到 403（e2e 实测）。这里只暴露"有已生效评估单的任务"
 * 这一最小投影，不把任务管理面交给 VIEWER。
 */
@Mapper
public interface ResultTaskMapper {

  @Select(
      """
      <script>
      SELECT t.id AS task_id, t.name AS task_name, t.year, t.quarter, t.deadline, t.status,
             COUNT(ts.id) AS result_count
      FROM eval_task t
      JOIN task_supplier ts ON ts.task_id = t.id AND """ + " " + EffectiveResults.CONDITION + """
      <where>
        <if test="status != null and status != ''"> AND t.status = #{status} </if>
        <if test="keyword != null and keyword != ''"> AND t.name LIKE CONCAT('%', #{keyword}, '%') </if>
      </where>
      GROUP BY t.id, t.name, t.year, t.quarter, t.deadline, t.status
      ORDER BY t.id DESC
      LIMIT #{size} OFFSET #{offset}
      </script>
      """)
  List<ResultTaskRow> findTasksWithResults(
      @Param("status") String status,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>
      SELECT COUNT(*) FROM eval_task t
      WHERE EXISTS (
        SELECT 1 FROM task_supplier ts WHERE ts.task_id = t.id AND """ + " " + EffectiveResults.CONDITION + """
      )
      <if test="status != null and status != ''"> AND t.status = #{status} </if>
      <if test="keyword != null and keyword != ''"> AND t.name LIKE CONCAT('%', #{keyword}, '%') </if>
      </script>
      """)
  long countTasksWithResults(
      @Param("status") String status, @Param("keyword") String keyword);
}
