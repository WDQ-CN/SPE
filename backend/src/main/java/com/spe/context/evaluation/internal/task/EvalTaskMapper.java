package com.spe.context.evaluation.internal.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `eval_task` 的 Mapper（属主上下文内可见，05 §8.3）。
 *
 * <p><b>状态迁移一律带前置条件</b>（06 §2.4 / ADR-5）：发布是
 * `WHERE id=? AND status='DRAFT' AND version=?` 一条语句完成判断与迁移；不提供"查出实体改字段再
 * updateById"的全字段覆盖写法。
 */
@Mapper
public interface EvalTaskMapper extends BaseMapper<EvalTask> {

  String COLUMNS =
      "id, name, year, quarter, template_version_id, deadline, status, version";

  @Select("SELECT " + COLUMNS + " FROM eval_task WHERE id = #{id}")
  Optional<EvalTask> findById(@Param("id") long id);

  /**
   * **当前读**并锁住任务行（CLAUDE 默认约定第 6 条）：凡"按状态决定后续动作"的地方都必须读最新状态，
   * 否则 REPEATABLE READ 的快照会让两个事务同时看到 DRAFT 而都去发布。
   */
  @Select("SELECT " + COLUMNS + " FROM eval_task WHERE id = #{id} FOR UPDATE")
  Optional<EvalTask> findByIdForUpdate(@Param("id") long id);

  /**
   * 编辑草稿（守卫 UPDATE）：状态必须是 DRAFT、且 {@code version} 未被别人改过。
   *
   * <p>0 行有两种含义——并发改动（3001）或状态已不是草稿（2020）：调用方用 {@link #findById} 区分，
   * 见 `TaskService.updateDraft`。
   */
  @Update(
      """
      UPDATE eval_task
      SET name = #{name}, year = #{year}, quarter = #{quarter},
          template_version_id = #{templateVersionId}, deadline = #{deadline}, version = version + 1
      WHERE id = #{id} AND status = 'DRAFT' AND version = #{version}
      """)
  int updateDraft(
      @Param("id") long id,
      @Param("version") int version,
      @Param("name") String name,
      @Param("year") int year,
      @Param("quarter") int quarter,
      @Param("templateVersionId") long templateVersionId,
      @Param("deadline") LocalDate deadline);

  /** 列表分页（13 §E18 的 GET /api/tasks）：名称包含匹配 + 可选状态；ORDER BY id DESC（新任务在前）。 */
  @Select(
      """
      <script>
      SELECT id, name, year, quarter, template_version_id, deadline, status, version
      FROM eval_task
      <where>
        <if test="status != null"> AND status = #{status} </if>
        <if test="keyword != null and keyword != ''"> AND name LIKE CONCAT('%', #{keyword}, '%') </if>
      </where>
      ORDER BY id DESC LIMIT #{size} OFFSET #{offset}
      </script>
      """)
  List<EvalTask> findPage(
      @Param("status") String status,
      @Param("keyword") String keyword,
      @Param("offset") long offset,
      @Param("size") int size);

  @Select(
      """
      <script>
      SELECT COUNT(*) FROM eval_task
      <where>
        <if test="status != null"> AND status = #{status} </if>
        <if test="keyword != null and keyword != ''"> AND name LIKE CONCAT('%', #{keyword}, '%') </if>
      </where>
      </script>
      """)
  long countByFilter(@Param("status") String status, @Param("keyword") String keyword);

  /** 发布（T1）：DRAFT → IN_PROGRESS，0 行 → 3001/2020（同上）。 */
  @Update(
      "UPDATE eval_task SET status = 'IN_PROGRESS', version = version + 1"
          + " WHERE id = #{id} AND status = 'DRAFT' AND version = #{version}")
  int publish(@Param("id") long id, @Param("version") int version);

  /**
   * 01 J4 的**当前读**（13 §E10 方案 A）：取"同周期内其它非取消任务"的 id 并加锁。
   *
   * <p>⚠️ **J4 没有数据库兜底**：`status <> 'CANCELED'` 是**条件唯一**，唯一键表达不了、MySQL 也没有
   * 部分索引（13 §E10）。这里能做的只是把窗口压到"两个发布事务同时开始"，**不能根除**——所以这是一条
   * **已登记的风险**（11 §9、13 §R1），不是"已经解决"。
   *
   * <p>用 `FOR SHARE` 而不是 `FOR UPDATE`：共享锁与"另一个发布事务的共享锁"**相容**，
   * 所以两个发布仍可能并行通过校验并各自插入——这正是那条风险的含义。换成 `FOR UPDATE` 能把它们
   * 真正串行化，但会对"全表扫描 + 期间所有任务行加写锁"付出代价（13 §E10 已把方案 B/C 判为不划算）。
   */
  @Select(
      "SELECT id FROM eval_task WHERE year = #{year} AND quarter = #{quarter}"
          + " AND status <> 'CANCELED' AND id <> #{excludeTaskId} ORDER BY id FOR SHARE")
  List<Long> lockOtherTaskIdsInPeriod(
      @Param("year") int year, @Param("quarter") int quarter, @Param("excludeTaskId") long excludeTaskId);
}
